package io.github.zeuspizza.yoriwake.gradle

import io.github.zeuspizza.yoriwake.agent.contract.AgentContract
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertEquals

/**
 * Declared dependencies: paths a test or a task depends on in ways coverage cannot see. A changed
 * path a declaration matches runs its declarers, whatever selection would otherwise have dropped.
 */
class DeclaredDependenciesFunctionalTest : FunctionalTestSupport() {

    /** BetaTest declares it depends on what is under `docker/`, which no class names. */
    private val declaringSecondTest = "src/test/java/dev/sample/BetaTest.java" to """
        package dev.sample;
        import org.junit.jupiter.api.Tag;
        import org.junit.jupiter.api.Test;
        import static org.junit.jupiter.api.Assertions.assertEquals;
        @Tag("yoriwake-depends-on:/docker/**")
        class BetaTest {
            @Test void passes() { assertEquals(3, new Beta().thrice(1)); }
        }
    """.trimIndent()

    private val ignoringDocker = """yoriwake { pathIgnore.add("docker/**") }"""

    private val fixture = "docker/fixture.json" to "{\"rows\": 1}\n"

    private fun app(dir: File) = File(dir, "app")

    /**
     * Captures `:app`, then changes `docker/fixture.json`, which the build ignores: without a
     * declaration the change runs nothing, as for a file no test reads.
     */
    private fun fixtureChangedAfterCapture(dir: File, appBuild: String, vararg sources: Pair<String, String>) {
        build(
            dir, "build.gradle.kts" to "", fixture,
            "app/build.gradle.kts" to appBuild + System.lineSeparator() + ignoringDocker,
            *arrayOf(oneClass, oneTest, secondClass, *sources).map { (path, content) -> "app/$path" to content }
                .toTypedArray(),
        )
        File(dir, "settings.gradle.kts").writeText("rootProject.name = \"sample\"\ninclude(\"app\")\n")
        committed(dir)
        runner(dir, ":app:test").build()
        File(app(dir), "build/test-results").deleteRecursively()
        File(dir, "docker/fixture.json").writeText("{\"rows\": 2}\n")
    }

    private fun rowReason(dir: File, test: String): String {
        val mapDir = File(dir, ".gradle/yoriwake").listFiles()!!.single(File::isDirectory)
        return File(mapDir, AgentContract.DECISIONS_FILE).readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .single { test in it }.split("\t")[2]
    }

    @Test
    fun `a changed path no class names selects the test that declares it`(@TempDir dir: File) {
        fixtureChangedAfterCapture(dir, minimalBuild, declaringSecondTest)

        val output = runner(dir, ":app:test", "-Pyoriwake.select").build().output

        assertEquals(setOf("dev.sample.BetaTest"), ranTests(app(dir)), output)
        assertEquals("DECLARES_CHANGED_PATH", rowReason(dir, "BetaTest"))
    }

    @Test
    fun `without the declaration, the same change runs neither test`(@TempDir dir: File) {
        fixtureChangedAfterCapture(dir, minimalBuild, secondTest)

        runner(dir, ":app:test", "-Pyoriwake.select").build()

        assertEquals(emptySet(), ranTests(app(dir)))
    }

    @Test
    fun `a path the build declares for a task runs that task in full`(@TempDir dir: File) {
        val declaring = minimalBuild + System.lineSeparator() + """yoriwake { dependsOn("test", "/docker/**") }"""
        fixtureChangedAfterCapture(dir, declaring, secondTest)

        val output = runner(dir, ":app:test", "-Pyoriwake.select").build().output

        assertEquals(setOf("dev.sample.AlphaTest", "dev.sample.BetaTest"), ranTests(app(dir)), output)
        assertEquals("declared-path-changed", decisionNotes(dir)[AgentContract.REFUSAL_KIND_NOTE])
        assertContains(output, "docker/fixture.json changed, which dependsOn declares")
    }

    @Test
    fun `a declaration that matches every path fails the build naming it`(@TempDir dir: File) {
        val everything = minimalBuild + System.lineSeparator() + """yoriwake { dependsOn("test", "**") }"""
        build(dir, "build.gradle.kts" to everything, oneClass, oneTest)

        val output = runner(dir, "test").buildAndFail().output

        assertContains(output, "dependsOn holds 'test=**', which has a glob that matches every path")
    }
}
