package io.github.zeuspizza.yoriwake.gradle

import io.github.zeuspizza.yoriwake.agent.contract.AgentContract
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A test runtime classpath that resolves a dependency to another jar than the capture's runs every
 * test, whatever changed it: a file `pathIgnore` leaves out, a property, or nothing in the tree.
 */
class ChangedDependenciesFunctionalTest : FunctionalTestSupport() {

    /** Runs first by name and passes on any version; loads nothing a change to Beta reaches. */
    private val versionTest = "src/test/java/dev/sample/AaVersionTest.java" to """
        package dev.sample;
        import org.apache.commons.lang3.StringUtils;
        import org.junit.jupiter.api.Test;
        import static org.junit.jupiter.api.Assertions.assertNotNull;
        class AaVersionTest {
            @Test void loads() { assertNotNull(StringUtils.class.getPackage().getImplementationVersion()); }
        }
    """.trimIndent()

    /** commons-lang3 at the version [versionFrom] gives, and the two-class sample; committed and captured. */
    private fun captured(dir: File, versionFrom: String, vararg sources: Pair<String, String>, args: List<String> = emptyList()) {
        val buildScript = minimalBuild + "\n" + versionFrom +
            "\ndependencies { testImplementation(\"org.apache.commons:commons-lang3:${'$'}lang3\") }\n"
        build(dir, "build.gradle.kts" to buildScript, oneClass, oneTest, secondClass, secondTest, versionTest, classOrderByName, *sources)
        committed(dir)
        runner(dir, "test", *args.toTypedArray()).build()
    }

    private fun select(dir: File, vararg args: String): String {
        File(dir, "build/test-results").deleteRecursively()
        return runner(dir, "test", "-Pyoriwake.select", "-Pyoriwake.base=HEAD", *args).build().output
    }

    private val fromProperties = """
        val lang3 = file("versions.properties").readLines().first { it.startsWith("commons-lang3=") }.substringAfter("=")
        yoriwake { pathIgnore.addAll("**") }
    """.trimIndent()

    private val fromProjectProperty = """val lang3 = providers.gradleProperty("lang3").getOrElse("3.17.0")"""

    private fun table(dir: File) =
        File(File(dir, ".gradle/yoriwake").listFiles()!!.single(File::isDirectory), "resource-digests")

    private fun assertRanEverything(dir: File, output: String) {
        assertTrue("dev.sample.AaVersionTest" in ranTests(dir), output)
        assertEquals("dependencies-changed", decisionNotes(dir)[AgentContract.REFUSAL_KIND_NOTE], output)
        assertTrue(output.lines().any { it.startsWith("[yoriwake] :test: ") && "commons-lang3" in it }, output)
    }

    @Test
    fun `a dependency version bumped in a properties file the build loads runs every test`(@TempDir dir: File) {
        captured(dir, fromProperties, "versions.properties" to "commons-lang3=3.17.0\n")
        File(dir, "versions.properties").writeText("commons-lang3=3.16.0\n")

        assertRanEverything(dir, select(dir))
    }

    @Test
    fun `a dependency a project property resolves to another version runs every test`(@TempDir dir: File) {
        captured(dir, fromProjectProperty, args = listOf("-Plang3=3.17.0"))
        changeBeta(dir)

        assertRanEverything(dir, select(dir, "-Plang3=3.16.0"))
    }

    @Test
    fun `an unchanged classpath selects as before`(@TempDir dir: File) {
        captured(dir, fromProjectProperty)
        changeBeta(dir)

        repeat(2) {
            val output = select(dir)

            assertEquals(setOf("dev.sample.BetaTest"), ranTests(dir), output)
            assertEquals(null, decisionNotes(dir)[AgentContract.REFUSAL_KIND_NOTE], output)
        }
    }

    @Test
    fun `a map without dependency rows runs every test once`(@TempDir dir: File) {
        captured(dir, fromProjectProperty)
        val rows = table(dir).readLines()
        assertTrue(rows.any { it.startsWith("dependency:") }, rows.joinToString("\n"))
        table(dir).writeText(rows.filterNot { it.startsWith("dependency:") }.joinToString("") { "$it\n" })
        changeBeta(dir)

        val forced = select(dir)

        assertTrue("dev.sample.AaVersionTest" in ranTests(dir), forced)
        assertEquals("classpath-files-unrecorded", decisionNotes(dir)[AgentContract.REFUSAL_KIND_NOTE], forced)
        assertContains(forced, "records no dependencies")

        val narrowed = select(dir)

        assertEquals(setOf("dev.sample.BetaTest"), ranTests(dir), narrowed)
        assertEquals(null, decisionNotes(dir)[AgentContract.REFUSAL_KIND_NOTE], narrowed)
    }
}
