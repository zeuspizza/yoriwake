package io.github.zeuspizza.yoriwake.gradle

import io.github.zeuspizza.yoriwake.agent.contract.AgentContract
import io.github.zeuspizza.yoriwake.gradle.capture.MapProvenance
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A run given a trusted-map list narrows only from a map whose exact content the list names. */
class MapProvenanceFunctionalTest : FunctionalTestSupport() {

    private fun mapDir(dir: File) = File(dir, ".gradle/yoriwake").listFiles()!!.single(File::isDirectory)

    private fun writtenDigest(dir: File) =
        File(mapDir(dir), AgentContract.MAP_DIGEST_FILE).readText().trim().removePrefix("sha256 ")

    /** Under `build/`, so the list is build output and never part of the change set. */
    private fun listing(dir: File, digest: String?, name: String = mapDir(dir).name): String {
        val file = File(dir, "build/trusted.tsv").also { it.parentFile.mkdirs() }
        file.writeText(digest?.let { "$name\t$it\n" }.orEmpty())
        return "-Pyoriwake.trustedMaps=${file.absolutePath}"
    }

    /** Alpha and Beta with their tests, committed, captured; then Beta changed, which BetaTest alone reaches. */
    private fun recorded(dir: File, vararg extra: Pair<String, String>, ignored: String = "") {
        build(dir, "build.gradle.kts" to minimalBuild, oneClass, oneTest, secondClass, secondTest, classOrderByName, *extra)
        ignoreBuildOutputs(dir)
        File(dir, ".gitignore").appendText(ignored)
        git(dir, "init")
        git(dir, "-c", "user.email=t@example.com", "-c", "user.name=t", "commit", "--allow-empty", "-m", "base")
        commit(dir, "sample")
        runner(dir, "test").build()
    }

    private fun select(dir: File, vararg args: String): String {
        File(dir, "build/test-results").deleteRecursively()
        return runner(dir, "test", "-Pyoriwake.select", *args).build().output
    }

    private val both = setOf("dev.sample.AlphaTest", "dev.sample.BetaTest")

    @Test
    fun `a run with no trusted-map list selects as before`(@TempDir dir: File) {
        recorded(dir)
        changeBeta(dir)

        val output = select(dir)

        assertEquals(setOf("dev.sample.BetaTest"), ranTests(dir))
        assertFalse("trusted-map list" in output)
    }

    @Test
    fun `a map the list names narrows`(@TempDir dir: File) {
        recorded(dir)
        val trusted = listing(dir, writtenDigest(dir))
        changeBeta(dir)

        select(dir, trusted)

        assertEquals(setOf("dev.sample.BetaTest"), ranTests(dir))
        assertNull(decisionNotes(dir)[AgentContract.REFUSAL_KIND_NOTE])
    }

    @Test
    fun `a map that differs from its trusted digest runs every test`(@TempDir dir: File) {
        recorded(dir)
        val trusted = listing(dir, writtenDigest(dir))
        File(mapDir(dir), AgentContract.COVERAGE_FILE).appendText("\n")
        changeBeta(dir)

        val output = select(dir, trusted)

        assertEquals(both, ranTests(dir))
        assertEquals("map-untrusted", decisionNotes(dir)[AgentContract.REFUSAL_KIND_NOTE])
        assertContains(output, "the map is not what the trusted-map list vouches for")
    }

    @Test
    fun `a crafted map carrying an honest map's digest file is untrusted`(@TempDir dir: File) {
        // The forger's map says BetaTest executed nothing, and keeps the honest map-digest beside it.
        recorded(dir)
        val trusted = listing(dir, writtenDigest(dir))
        val coverage = File(mapDir(dir), AgentContract.COVERAGE_FILE)
        coverage.writeText(coverage.readLines().joinToString("\n", postfix = "\n") { line ->
            val fields = line.split('\t')
            if (fields.size == 4 && "BetaTest" in fields[3]) listOf(fields[0], fields[1], "", fields[3]).joinToString("\t") else line
        })
        changeBeta(dir)

        select(dir, trusted)

        assertEquals(both, ranTests(dir))
        assertEquals("map-untrusted", decisionNotes(dir)[AgentContract.REFUSAL_KIND_NOTE])
    }

    @Test
    fun `a map the list does not name runs every test`(@TempDir dir: File) {
        recorded(dir)
        val empty = listing(dir, null)
        changeBeta(dir)

        val output = select(dir, empty)

        assertEquals(both, ranTests(dir))
        assertEquals("map-unverified", decisionNotes(dir)[AgentContract.REFUSAL_KIND_NOTE])
        assertContains(output, "the trusted-map list does not name the map")
    }

    @Test
    fun `a list that names only another map leaves this one unverified`(@TempDir dir: File) {
        recorded(dir)
        val other = listing(dir, writtenDigest(dir), name = "other-test-00000000")
        changeBeta(dir)

        select(dir, other)

        assertEquals("map-unverified", decisionNotes(dir)[AgentContract.REFUSAL_KIND_NOTE])
    }

    @Test
    fun `the capture after an untrusted map keeps none of its records`(@TempDir dir: File) {
        // A selecting run keeps the loaded-class union it finds, so only removing the map drops it.
        recorded(dir)
        val loaded = File(mapDir(dir), AgentContract.LOADED_FILE)
        loaded.writeText("dev.sample.Ghost\n")
        changeBeta(dir)

        select(dir, listing(dir, null))

        assertFalse(loaded.isFile && "Ghost" in loaded.readText(), "the untrusted map's union survived")
        // And what the run recorded is listed by the digest it wrote.
        assertEquals(MapProvenance.digest(mapDir(dir)), writtenDigest(dir))
    }

    @Test
    fun `an untrusted map whose snapshot drifted still reports its provenance`(@TempDir dir: File) {
        // The ignored fixture changed after the capture, so the configured change set holds it; the
        // map is cleared before the freshness check would compare against it.
        recorded(dir, "fixture.local" to "one", ignored = System.lineSeparator() + "*.local")
        File(dir, "fixture.local").writeText("two")
        changeBeta(dir)

        select(dir, listing(dir, null))

        assertEquals("map-unverified", decisionNotes(dir)[AgentContract.REFUSAL_KIND_NOTE])
    }

    @Test
    fun `a missing or malformed trusted-map list fails the build`(@TempDir dir: File) {
        recorded(dir)

        val missing = runner(dir, "test", "-Pyoriwake.select", "-Pyoriwake.trustedMaps=missing.tsv")
            .buildAndFail().output
        assertContains(missing, "-Pyoriwake.trustedMaps=missing.tsv")

        val file = File(dir, "build/trusted.tsv").also { it.writeText("not a digest line\n") }
        val malformed = runner(dir, "test", "-Pyoriwake.select", "-Pyoriwake.trustedMaps=${file.absolutePath}")
            .buildAndFail().output
        assertContains(malformed, "\"not a digest line\"")
    }

    @Test
    fun `a recording run writes the map's digest and checks nothing`(@TempDir dir: File) {
        recorded(dir)
        File(mapDir(dir), AgentContract.COVERAGE_FILE).appendText("\n")

        val output = runner(dir, "test", "--rerun", listing(dir, null)).build().output

        assertFalse("trusted-map list" in output)
        assertEquals(MapProvenance.digest(mapDir(dir)), writtenDigest(dir))
    }

    @Test
    fun `a cached narrowed result is not reused under another verdict`(@TempDir dir: File) {
        recorded(dir)
        changeBeta(dir)
        val list = File(dir, "build/trusted.tsv")
        select(dir, listing(dir, writtenDigest(dir)), "--build-cache")
        assertEquals(setOf("dev.sample.BetaTest"), ranTests(dir))
        list.writeText("")

        File(dir, "build/test-results").deleteRecursively()
        val second = runner(dir, "test", "-Pyoriwake.select", "-Pyoriwake.trustedMaps=${list.absolutePath}", "--build-cache")
            .build()

        assertEquals(TaskOutcome.SUCCESS, second.task(":test")?.outcome)
        assertEquals(both, ranTests(dir))
        assertEquals("map-unverified", decisionNotes(dir)[AgentContract.REFUSAL_KIND_NOTE])
    }

    @Test
    fun `a reused configuration still checks the map`(@TempDir dir: File) {
        recorded(dir)
        val trusted = listing(dir, writtenDigest(dir))
        changeBeta(dir)
        select(dir, trusted)
        File(mapDir(dir), AgentContract.COVERAGE_FILE).appendText("\n")

        val output = select(dir, trusted)

        assertContains(output, "Reusing configuration cache")
        assertEquals("map-untrusted", decisionNotes(dir)[AgentContract.REFUSAL_KIND_NOTE])
    }

    @Test
    fun `a failure carried into the map leaves a digest that matches it`(@TempDir dir: File) {
        // A filtered run is not kept, but the failure it saw is marked in the map; the digest follows.
        recorded(
            dir,
            "build.gradle.kts" to minimalBuild.replace(
                "tasks.test { useJUnitPlatform() }",
                "tasks.test { useJUnitPlatform(); systemProperty(\"fail\", project.hasProperty(\"fail\").toString()) }",
            ),
            "src/test/java/dev/sample/AlphaTest.java" to """
                package dev.sample;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertEquals;
                class AlphaTest {
                    @Test void passes() { assertEquals(Boolean.getBoolean("fail") ? 0 : 2, new Alpha().twice(1)); }
                }
            """.trimIndent(),
        )
        val before = writtenDigest(dir)

        runner(dir, "test", "--tests", "*AlphaTest*", "-Pfail").buildAndFail()

        assertTrue(before != writtenDigest(dir), "the carried failure left the old digest")
        assertEquals(MapProvenance.digest(mapDir(dir)), writtenDigest(dir))
        changeBeta(dir)
        select(dir, listing(dir, writtenDigest(dir)))
        assertNull(decisionNotes(dir)[AgentContract.REFUSAL_KIND_NOTE])
    }

    @Test
    fun `explain reports the refusal a listed run would make`(@TempDir dir: File) {
        recorded(dir)
        changeBeta(dir)

        val output = runner(dir, "yoriwakeExplainTest", listing(dir, null)).build().output

        assertContains(output, "would run everything: the trusted-map list does not name the map")
        assertContains(File(mapDir(dir), "explain.json").readText(), "\"refusalKind\": \"map-unverified\"")
    }

    @Test
    fun `explain on a declined task says so, with a trusted-map list too`(@TempDir dir: File) {
        val disabled = minimalBuild + "\n" + """
            tasks.test { extensions.getByType<JacocoTaskExtension>().isEnabled = false }
        """.trimIndent()
        build(dir, "build.gradle.kts" to disabled, oneClass, oneTest)
        val list = File(dir, "build/trusted.tsv").also { it.parentFile.mkdirs(); it.writeText("") }

        listOf(emptyList(), listOf("-Pyoriwake.trustedMaps=${list.absolutePath}")).forEach { extra ->
            val output = runner(dir, "yoriwakeExplainTest", *extra.toTypedArray()).build().output
            assertContains(output, "Nothing is captured or selected for this task")
        }
    }
}
