package io.github.zeuspizza.yoriwake.gradle

import io.github.zeuspizza.yoriwake.agent.contract.AgentContract
import io.github.zeuspizza.yoriwake.gradle.report.Audit
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A test task Develocity runs or selects tests for: yoriwake declines, and leaves everything as it was. */
class DevelocityFunctionalTest : FunctionalTestSupport() {

    /**
     * A `develocity` task extension shaped like the real plugin's 4.6.0 one:
     * `getTestDistribution()` and `getPredictiveTestSelection()`, each with a `getEnabled()`
     * property. `-Ptd` and `-Ppts` switch them on from the build script; [extra] adds to the task.
     */
    private fun standInBuild(extra: String = "") = minimalBuild.replace(
        "tasks.test { useJUnitPlatform() }",
        """
        class StandInFeature(val enabled: Property<Boolean>)
        class StandInDevelocity(val testDistribution: StandInFeature, val predictiveTestSelection: StandInFeature)
        tasks.test {
            useJUnitPlatform()
            systemProperty("probe.out", layout.buildDirectory.file("probe.txt").get().asFile.absolutePath)
            val flag = { objects.property(Boolean::class.java).convention(false) }
            val develocity = StandInDevelocity(StandInFeature(flag()), StandInFeature(flag()))
            extensions.add("develocity", develocity)
            develocity.testDistribution.enabled.set(providers.gradleProperty("td").isPresent)
            develocity.predictiveTestSelection.enabled.set(providers.gradleProperty("pts").isPresent)
            $extra
        }
        """.trimIndent(),
    )

    /** Writes what its test JVM was started with, for the assertions below. */
    private val probeTest = "src/test/java/dev/sample/ZProbeTest.java" to """
        package dev.sample;
        import org.junit.jupiter.api.Test;
        class ZProbeTest {
            @Test void probe() throws Exception {
                java.nio.file.Files.writeString(java.nio.file.Path.of(System.getProperty("probe.out")),
                    "main=" + System.getProperty("sun.java.command") + "\n"
                        + "args=" + java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments() + "\n"
                        + "mapdir=" + System.getProperty("yoriwake.map.dir") + "\n"
                        + "changefile=" + System.getProperty("yoriwake.change.file") + "\n");
            }
        }
    """.trimIndent()

    private fun recorded(dir: File, buildScript: String, vararg extra: Pair<String, String>) {
        build(dir, "build.gradle.kts" to buildScript, oneClass, oneTest, secondClass, secondTest, classOrderByName, probeTest, *extra)
        ignoreBuildOutputs(dir)
        git(dir, "init")
        git(dir, "-c", "user.email=t@example.com", "-c", "user.name=t", "commit", "--allow-empty", "-m", "base")
        commit(dir, "sample")
        runner(dir, "test").build()
    }

    private fun mapDir(dir: File) = File(dir, ".gradle/yoriwake").listFiles()!!.single(File::isDirectory)

    /** Every file of the map directory but the run's own scratch and reports, as bytes. */
    private fun mapState(dir: File): Map<String, List<Byte>> = mapDir(dir).walkTopDown()
        .filter { it.isFile && "/raw/" !in it.path && !it.name.startsWith(AgentContract.DECISIONS_FILE) }
        // The pending start files are written at configuration, on every run alike.
        .filter { it.name != Audit.TaskFacts.FILE && !it.name.endsWith(".pending") && !it.name.endsWith(".dated") }
        .filter { it.name != "explain.json" }
        .associate { it.relativeTo(mapDir(dir)).path to it.readBytes().toList() }

    private fun probe(dir: File) = File(dir, "build/probe.txt").readText()

    private val all = setOf("dev.sample.AlphaTest", "dev.sample.BetaTest", "dev.sample.ZProbeTest")

    private fun runDeclined(dir: File, vararg args: String): String {
        File(dir, "build/test-results").deleteRecursively()
        return runner(dir, "test", *args).build().output
    }

    private fun assertLeftAlone(dir: File, before: Map<String, List<Byte>>, output: String, token: String) {
        assertEquals(all, ranTests(dir))
        assertEquals(before, mapState(dir), "a declined run changed the map")
        val markers = mapDir(dir).listFiles().orEmpty().map { it.name }.filter { it.endsWith(".marker") }
        assertEquals(emptyList(), markers, "a declined run left markers behind")
        val args = probe(dir).lines().first { it.startsWith("args=") }
        assertFalse("yoriwake-agent" in args, "the yoriwake agent was attached: $args")
        // With no map directory, the inert filter on the classpath writes nothing either.
        assertFalse(File(mapDir(dir), AgentContract.DECISIONS_FILE).exists(), "a declined run wrote decisions")
        assertContains(probe(dir), "mapdir=null")
        assertContains(probe(dir), "changefile=null")
        assertContains(output, token)
    }

    @Test
    fun `a test task with Test Distribution enabled declines and leaves the map byte-identical`(@TempDir dir: File) {
        recorded(dir, standInBuild())
        val before = mapState(dir)
        changeBeta(dir)

        assertLeftAlone(dir, before, runDeclined(dir, "-Ptd"), "develocity-test-distribution")
        assertLeftAlone(dir, before, runDeclined(dir, "-Ptd", "-Pyoriwake.select"), "develocity-test-distribution")
    }

    @Test
    fun `a test task with Develocity test selection enabled declines and leaves the map byte-identical`(@TempDir dir: File) {
        recorded(dir, standInBuild())
        val before = mapState(dir)
        changeBeta(dir)

        val output = runDeclined(dir, "-Ppts", "-Pyoriwake.select")

        assertLeftAlone(dir, before, output, "develocity-test-selection")
        assertContains(output, "Develocity Predictive Test Selection is enabled on this task")
    }

    @Test
    fun `a declined run starts its test JVM without the yoriwake agent`(@TempDir dir: File) {
        recorded(dir, standInBuild())

        runDeclined(dir, "-Ptd", "-Pyoriwake.internal.loaded")

        val args = probe(dir).lines().first { it.startsWith("args=") }
        // The host's JaCoCo agent stays; yoriwake's own, added at configuration on this run, goes.
        assertFalse("yoriwake-agent" in args, args)
    }

    @Test
    fun `a declined run's decode reports the decline and nothing else`(@TempDir dir: File) {
        recorded(dir, standInBuild())

        val output = runDeclined(dir, "-Ptd")

        assertContains(output, "Develocity declined this run, so the map is left exactly as it was")
        assertFalse("recorded no coverage" in output, output)
        assertFalse(File(mapDir(dir), Audit.TaskFacts.FILE).readText().contains("recordedCoverage=false"))
    }

    @Test
    fun `a declined selecting run leaves an unlisted map byte-identical`(@TempDir dir: File) {
        // The map is of unknown age (no capture stamp), and the trusted-map list does not name it:
        // the fallback and the provenance check would each act alone; neither may.
        recorded(dir, standInBuild())
        File(mapDir(dir), "capture-commit").delete()
        val before = mapState(dir)
        val list = File(dir, "build/trusted.tsv").also { it.writeText("") }
        changeBeta(dir)

        val output = runDeclined(dir, "-Ppts", "-Pyoriwake.select", "-Pyoriwake.trustedMaps=${list.absolutePath}")

        assertLeftAlone(dir, before, output, "develocity-test-selection")
        assertFalse("map-unverified" in output || "stamp-absent" in output, output)
    }

    @Test
    fun `a declined run with in-JVM parallelism keeps its token and the host's JaCoCo`(@TempDir dir: File) {
        recorded(dir, standInBuild("systemProperty(\"junit.jupiter.execution.parallel.enabled\", \"true\")"))
        val before = mapState(dir)
        File(dir, "build/jacoco").deleteRecursively()

        val output = runDeclined(dir, "-Ptd")

        assertLeftAlone(dir, before, output, "develocity-test-distribution")
        assertFalse("in-jvm-parallelism" in output, output)
        assertTrue(File(dir, "build/jacoco/test.exec").isFile, "the host's JaCoCo was switched off")
    }

    @Test
    fun `a feature enabled once the task graph is ready still declines`(@TempDir dir: File) {
        recorded(
            dir,
            standInBuild() + "\n" + """
                gradle.taskGraph.whenReady {
                    if (providers.gradleProperty("late").isPresent) {
                        (tasks.test.get().extensions.getByName("develocity") as StandInDevelocity)
                            .predictiveTestSelection.enabled.set(true)
                    }
                }
            """.trimIndent(),
        )
        val before = mapState(dir)

        assertLeftAlone(dir, before, runDeclined(dir, "-Plate"), "develocity-test-selection")
        // Reused from the configuration cache, it still declines.
        assertLeftAlone(dir, before, runDeclined(dir, "-Plate"), "develocity-test-selection")
    }

    @Test
    fun `with both features off the extension changes nothing`(@TempDir dir: File) {
        recorded(dir, standInBuild())
        changeBeta(dir)
        File(dir, "build/test-results").deleteRecursively()

        val output = runner(dir, "test", "-Pyoriwake.select").build().output

        assertEquals(setOf("dev.sample.BetaTest", "dev.sample.ZProbeTest"), ranTests(dir))
        assertFalse("develocity-" in output, output)
    }

    @Test
    fun `an unreadable Develocity extension declines as undetermined`(@TempDir dir: File) {
        val broken = minimalBuild.replace(
            "tasks.test { useJUnitPlatform() }",
            """
            class Broken { fun getTestDistribution(): Any = throw IllegalStateException("no such switch here") }
            tasks.test {
                useJUnitPlatform()
                systemProperty("probe.out", layout.buildDirectory.file("probe.txt").get().asFile.absolutePath)
                if (providers.gradleProperty("broken").isPresent) extensions.add("develocity", Broken())
            }
            """.trimIndent(),
        )
        recorded(dir, broken)
        val before = mapState(dir)

        val output = runDeclined(dir, "-Pbroken")

        assertLeftAlone(dir, before, output, "develocity-undetermined")
        assertContains(output, "no such switch here")
    }

    @Test
    fun `explain and audit name the decline`(@TempDir dir: File) {
        recorded(dir, standInBuild())
        changeBeta(dir)
        runDeclined(dir, "-Ptd")

        runner(dir, "yoriwakeExplainTest", "-Ptd").build()
        assertContains(File(mapDir(dir), "explain.json").readText(), "\"refusalKind\": \"develocity-test-distribution\"")
        assertContains(runner(dir, "yoriwakeAuditTest").build().output, "develocity-test-distribution")
    }

    @Test
    fun `the real Develocity plugin running tests locally under Test Distribution is declined`(@TempDir dir: File) {
        // 4.6.0 with no server runs the partition locally through its own launcher, where 0.1.0
        // captured half the suite's coverage.
        val real = minimalBuild.replace(
            "tasks.test { useJUnitPlatform() }",
            """
            tasks.test {
                useJUnitPlatform()
                systemProperty("probe.out", layout.buildDirectory.file("probe.txt").get().asFile.absolutePath)
                develocity { testDistribution { enabled.set(providers.gradleProperty("td").isPresent) } }
            }
            """.trimIndent(),
        )
        build(dir, "build.gradle.kts" to real, oneClass, oneTest, secondClass, secondTest, classOrderByName, probeTest)
        File(dir, "settings.gradle.kts").writeText(
            "plugins { id(\"com.gradle.develocity\") version \"4.6.0\" }\nrootProject.name = \"sample\"\n"
        )
        ignoreBuildOutputs(dir)
        git(dir, "init")
        commit(dir, "sample")
        runner(dir, "test").build()
        val before = mapState(dir)

        val output = runDeclined(dir, "-Ptd")

        assertContains(probe(dir), "LauncherMain")
        assertLeftAlone(dir, before, output, "develocity-test-distribution")
    }
}
