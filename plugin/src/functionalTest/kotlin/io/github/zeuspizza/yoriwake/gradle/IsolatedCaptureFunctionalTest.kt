package io.github.zeuspizza.yoriwake.gradle

import io.github.zeuspizza.yoriwake.agent.contract.AgentContract
import io.github.zeuspizza.yoriwake.gradle.capture.CoverageDecoder
import io.github.zeuspizza.yoriwake.gradle.change.WorkingTree
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `-Pyoriwake.isolatedCapture` gives each test class a JVM of its own while a run records the map,
 * and changes nothing else: a selecting run, or a run that captures nothing, keeps the host's own
 * fork settings. What the flag buys in selection is pinned in [JvmSharedFunctionalTest].
 */
class IsolatedCaptureFunctionalTest : FunctionalTestSupport() {

    /** The host's own fork settings, and a line that reports what the task ran with. */
    private fun hostBuild(extra: String = "") = minimalBuild.replace(
        "tasks.test { useJUnitPlatform() }",
        """
        tasks.test {
            useJUnitPlatform()
            maxParallelForks = 2
            $extra
            doLast { println("host forks: every=" + (this as Test).forkEvery + " parallel=" + maxParallelForks) }
        }
        """.trimIndent(),
    )

    private fun forks(output: String) =
        output.lines().single { it.startsWith("host forks:") }.removePrefix("host forks: ")

    private fun sample(
        dir: File,
        buildScript: String = hostBuild(),
        tests: List<Pair<String, String>> = listOf(oneTest, secondTest),
    ) {
        build(dir, "build.gradle.kts" to buildScript, oneClass, secondClass, *tests.toTypedArray())
        committed(dir)
    }

    @Test
    fun `a capture with the flag forks every test class and keeps the host's parallel forks`(
        @TempDir dir: File,
    ) {
        sample(dir)

        val output = runner(dir, "test", "-Pyoriwake.isolatedCapture").build().output

        assertEquals("every=1 parallel=2", forks(output))
        assertContains(output, "recording with a fresh test JVM per test class")
        val explained = runner(dir, "yoriwakeExplainTest", "-Pyoriwake.base=HEAD").build().output
        assertContains(explained, "the map was recorded with a fresh test JVM per test class")
    }

    @Test
    fun `a selecting run with the flag runs the host's tests with the host's fork settings`(
        @TempDir dir: File,
    ) {
        sample(dir)
        runner(dir, "test", "-Pyoriwake.isolatedCapture").build()
        changeBeta(dir)

        val output = runner(dir, "test", "-Pyoriwake.select", "-Pyoriwake.isolatedCapture").build().output

        assertEquals("narrowed", decisionNotes(dir)["outcome"], output)
        assertEquals("every=0 parallel=2", forks(output))
    }

    @Test
    fun `a selecting run that falls back to capturing keeps the host's fork settings`(@TempDir dir: File) {
        sample(dir)

        // No map yet: the run refuses, runs everything, and captures.
        val output = runner(dir, "test", "-Pyoriwake.select", "-Pyoriwake.isolatedCapture").build().output

        assertContains(output, "running everything, so this run also captures")
        assertEquals("every=0 parallel=2", forks(output))
    }

    @Test
    fun `a selecting run that falls back to capturing labels its map shared`(@TempDir dir: File) {
        // One test class, so its one JVM ran one class: only the run's own mode can say "shared".
        sample(dir, tests = listOf(oneTest))

        runner(dir, "test", "-Pyoriwake.select", "-Pyoriwake.isolatedCapture").build()

        val mapDir = File(dir, ".gradle/yoriwake").listFiles()!!.single(File::isDirectory)
        val modes = File(mapDir, "jvm-mode.tsv").readLines().filter(String::isNotBlank).map { it.substringAfter('\t') }
        assertEquals(setOf("shared"), modes.toSet())
    }

    private fun mapDir(dir: File) = File(dir, ".gradle/yoriwake").listFiles()!!.single(File::isDirectory)

    /** Every file the decode writes, by name, with its bytes. */
    private fun mapFiles(dir: File): Map<String, List<Byte>> = listOf(
        AgentContract.COVERAGE_FILE, AgentContract.POSITIONS_FILE, AgentContract.FIRST_TOUCH_FILE,
        AgentContract.NAMED_TOUCH_FILE, AgentContract.JVM_MODE_FILE, AgentContract.SCOPE_FILE,
        AgentContract.EFFECTIVE_SCOPE_FILE, AgentContract.MAP_SCHEMA_VERSION_FILE, AgentContract.LOADED_FILE,
        AgentContract.LOADED_PROVENANCE_FILE, AgentContract.LOADED_SCOPE_FILE, CoverageDecoder.CAPTURE_COMMIT_FILE,
        CoverageDecoder.CONSTANTS_FILE, CoverageDecoder.CLASS_DIGESTS_FILE, CoverageDecoder.ANNOTATION_DIGESTS_FILE,
        WorkingTree.SNAPSHOT_FILE,
    ).mapNotNull { name -> File(mapDir(dir), name).takeIf(File::isFile)?.let { name to it.readBytes().toList() } }
        .toMap()

    private fun modes(dir: File) =
        File(mapDir(dir), AgentContract.JVM_MODE_FILE).readLines().filter(String::isNotBlank)
            .map { it.substringAfter('\t') }.toSet()

    /** A change coverage can learn from, beside one it cannot see, so the run falls back and would capture. */
    private fun forceAFallback(dir: File) {
        changeBeta(dir)
        File(dir, "build.gradle.kts").appendText("\n// forces a full run\n")
    }

    private fun selecting(dir: File): String {
        File(dir, "build/test-results").deleteRecursively()
        return runner(dir, "test", "-Pyoriwake.select").build().output
    }

    @Test
    fun `a selecting run that falls back leaves an isolated map exactly as it was`(@TempDir dir: File) {
        sample(dir)
        runner(dir, "test", "-Pyoriwake.isolatedCapture").build()
        val recorded = mapFiles(dir)
        forceAFallback(dir)

        val output = selecting(dir)

        assertEquals("full-run", decisionNotes(dir)["outcome"], output)
        assertEquals(setOf("dev.sample.AlphaTest", "dev.sample.BetaTest"), ranTests(dir))
        assertEquals("every=0 parallel=2", forks(output))
        assertContains(output, "leaves it alone and nothing is instrumented")
        assertEquals(recorded, mapFiles(dir), "a fallback rewrote a map recorded in isolation")
        assertEquals(setOf("isolated"), modes(dir))

        // With what forced it gone, the next selecting run narrows from the map it left alone. The
        // change is committed, so only the base widened back to the map's older stamp still sees it.
        File(dir, "build.gradle.kts").writeText(hostBuild())
        commit(dir, "beta")
        selecting(dir)

        assertEquals("narrowed", decisionNotes(dir)["outcome"])
        assertEquals(setOf("dev.sample.BetaTest"), ranTests(dir))
    }

    @Test
    fun `a selecting run that falls back refreshes a shared map`(@TempDir dir: File) {
        sample(dir)
        runner(dir, "test").build()
        val recorded = mapFiles(dir)
        forceAFallback(dir)

        val output = selecting(dir)

        assertContains(output, "running everything, so this run also captures")
        assertTrue(
            recorded[AgentContract.POSITIONS_FILE] != mapFiles(dir)[AgentContract.POSITIONS_FILE],
            "the fallback did not re-record the shared map",
        )
        assertEquals(setOf("shared"), modes(dir))
    }

    @Test
    fun `a selecting run that falls back leaves a map that is partly isolated alone`(@TempDir dir: File) {
        sample(dir)
        runner(dir, "test").build()
        // A map that is partly isolated, written directly: a filtered isolated run no longer merges
        // into the map, and no other run leaves both modes in one map.
        // The mode is read per JVM that holds a position, so the isolated JVM holds one.
        val alpha = File(mapDir(dir), AgentContract.POSITIONS_FILE).readLines().first { "AlphaTest" in it }
        File(mapDir(dir), AgentContract.POSITIONS_FILE).appendText("earlier/worker-1\t1\t${alpha.substringAfterLast('\t')}\n")
        File(mapDir(dir), AgentContract.JVM_MODE_FILE).appendText("earlier/worker-1\tisolated\n")
        assertEquals(setOf("isolated", "shared"), modes(dir))
        val recorded = mapFiles(dir)
        forceAFallback(dir)

        val output = selecting(dir)

        assertEquals("full-run", decisionNotes(dir)["outcome"], output)
        assertContains(output, "leaves it alone and nothing is instrumented")
        assertEquals(recorded, mapFiles(dir), "a fallback rewrote a map partly recorded in isolation")
    }

    @Test
    fun `a selecting run that refuses over the map's age leaves an isolated map alone`(@TempDir dir: File) {
        sample(dir)
        runner(dir, "test", "-Pyoriwake.isolatedCapture").build()
        assertTrue(File(mapDir(dir), CoverageDecoder.CAPTURE_COMMIT_FILE).delete())
        val recorded = mapFiles(dir)

        val output = selecting(dir)

        assertContains(output, "the next recording run clears it")
        assertContains(output, "leaves it alone and nothing is instrumented")
        assertEquals(setOf("dev.sample.AlphaTest", "dev.sample.BetaTest"), ranTests(dir))
        assertEquals(recorded, mapFiles(dir), "a refused run rewrote a map recorded in isolation")
    }

    @Test
    fun `a selecting run git cannot report changes for leaves an isolated map alone`(@TempDir dir: File) {
        sample(dir)
        runner(dir, "test", "-Pyoriwake.isolatedCapture").build()
        val recorded = mapFiles(dir)
        // A corrupt index fails `git diff` and `git ls-files`, while the commits still resolve.
        File(dir, ".git/index").writeText("not an index")

        val output = selecting(dir)

        assertContains(output, "git could not report changes")
        assertEquals(setOf("dev.sample.AlphaTest", "dev.sample.BetaTest"), ranTests(dir))
        assertEquals(recorded, mapFiles(dir), "a run without a change set rewrote a map recorded in isolation")
        assertContains(output, "leaves it alone and nothing is instrumented")
    }

    @Test
    fun `the flag with the plugin disabled changes nothing`(@TempDir dir: File) {
        sample(dir)

        val output = runner(dir, "test", "-Pyoriwake.disabled", "-Pyoriwake.isolatedCapture").build().output

        assertEquals("every=0 parallel=2", forks(output))
    }

    @Test
    fun `the flag on a run declined over in-JVM parallelism changes nothing`(@TempDir dir: File) {
        sample(dir, hostBuild("systemProperty(\"junit.jupiter.execution.parallel.enabled\", \"true\")"))

        val output = runner(dir, "test", "-Pyoriwake.isolatedCapture").build().output

        assertContains(output, "in-JVM parallel test execution is enabled")
        assertEquals("every=0 parallel=2", forks(output))
    }

    @Test
    fun `an isolatedCapture value that is not a boolean fails the build and names the flag`(@TempDir dir: File) {
        sample(dir)

        val output = runner(dir, "test", "-Pyoriwake.isolatedCapture=yes").buildAndFail().output

        assertContains(output, "-Pyoriwake.isolatedCapture=yes is not a boolean")
    }
}
