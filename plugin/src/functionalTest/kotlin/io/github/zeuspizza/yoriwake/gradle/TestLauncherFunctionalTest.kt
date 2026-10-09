package io.github.zeuspizza.yoriwake.gradle

import org.gradle.tooling.GradleConnector
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertEquals

/**
 * An IDE that delegates a test run to Gradle asks through the Tooling API's test launcher, which
 * adds the class it names to the task's filter when it schedules the task: after the configuration
 * cache stored its entry, so only what runs at execution sees it. The tests the IDE asked for run
 * whatever the map says.
 *
 * Driven through a real Tooling API connection, with the plugin resolved from the repository this
 * build publishes to, since TestKit's plugin classpath does not reach a Tooling API build.
 */
class TestLauncherFunctionalTest : FunctionalTestSupport() {

    private val repository = File(
        requireNotNull(System.getProperty("yoriwake.localRepository")) {
            "the build did not say where it published the plugin; run this through Gradle"
        },
    )

    private val version = requireNotNull(System.getProperty("yoriwake.pluginVersion")) {
        "the build did not say which version it published; run this through Gradle"
    }

    private val alphaTests = "src/test/java/dev/sample/AlphaTest.java" to """
        package dev.sample;
        import org.junit.jupiter.api.Test;
        import static org.junit.jupiter.api.Assertions.assertEquals;
        class AlphaTest {
            @Test void adds() { assertEquals(2, new Alpha().twice(1)); }
            @Test void doubles() { assertEquals(6, new Alpha().twice(3)); }
        }
    """.trimIndent()

    private val publishedBuild = minimalBuild.replace(
        "id(\"io.github.zeuspizza.yoriwake\")",
        "id(\"io.github.zeuspizza.yoriwake\") version \"$version\"",
    )

    /** A committed sample with a map captured by an ordinary build of `test`. */
    private fun captured(dir: File) {
        build(dir, "build.gradle.kts" to publishedBuild, oneClass, alphaTests, secondClass, secondTest, classOrderByName)
        File(dir, "settings.gradle.kts").writeText(
            """
            pluginManagement {
                repositories {
                    exclusiveContent {
                        forRepository { maven { url = uri("${repository.toURI()}") } }
                        filter {
                            includeGroup("io.github.zeuspizza")
                            includeGroup("io.github.zeuspizza.yoriwake")
                        }
                    }
                    mavenCentral()
                }
            }
            rootProject.name = "sample"
            """.trimIndent(),
        )
        committed(dir)
        connected(dir) { it.newBuild().forTasks("test") }
        File(dir, "build/test-results").deleteRecursively()
    }

    /** Asks for AlphaTest alone, as an IDE does when a developer runs one test class. */
    private fun launchAlphaTest(dir: File, vararg args: String): String =
        connected(dir, *args) { it.newTestLauncher().withTaskAndTestClasses(":test", listOf("dev.sample.AlphaTest")) }

    /**
     * Runs one build through a fresh Tooling API connection, with the configuration cache on as
     * every functional build does, and returns its output. The connector's daemons stop with it.
     */
    private fun connected(
        dir: File,
        vararg args: String,
        operation: (org.gradle.tooling.ProjectConnection) -> org.gradle.tooling.ConfigurableLauncher<*>,
    ): String {
        val output = ByteArrayOutputStream()
        val connector = GradleConnector.newConnector()
            .forProjectDirectory(dir)
            .useGradleVersion(FunctionalGradle.version)
            .useGradleUserHomeDir(FunctionalGradle.testKitDir())
        try {
            connector.connect().use { connection ->
                val launcher = operation(connection)
                    .withArguments(*args, "--configuration-cache", "--stacktrace", "--no-watch-fs")
                    .setStandardOutput(output)
                    .setStandardError(output)
                when (launcher) {
                    is org.gradle.tooling.BuildLauncher -> launcher.run()
                    is org.gradle.tooling.TestLauncher -> launcher.run()
                    else -> error("not a launcher this test runs: $launcher")
                }
            }
        } catch (failed: org.gradle.tooling.GradleConnectionException) {
            throw AssertionError("the build failed:\n$output", failed)
        } finally {
            connector.disconnect()
        }
        return output.toString().also(::print)
    }

    /** The methods each test class ran, by class name; a method reported as skipped did not run. */
    private fun ranMethods(dir: File): Map<String, Set<String>> =
        File(dir, "build/test-results/test")
            .listFiles { f: File -> f.name.endsWith(".xml") }
            .orEmpty()
            .associate { report ->
                report.name.removePrefix("TEST-").removeSuffix(".xml") to
                    Regex("""<testcase name="([^"(]+)[^>]*?(/>|>(.*?)</testcase>)""", RegexOption.DOT_MATCHES_ALL)
                        .findAll(report.readText())
                        .filterNot { "<skipped" in it.groupValues[3] }
                        .map { it.groupValues[1] }
                        .toSet()
            }
            .filterValues { it.isNotEmpty() }

    @Test
    fun `a test class an IDE asks for runs every method under selection`(@TempDir dir: File) {
        captured(dir)
        changeBeta(dir)

        val output = launchAlphaTest(dir, "-Pyoriwake.select")

        assertEquals(mapOf("dev.sample.AlphaTest" to setOf("adds", "doubles")), ranMethods(dir), output)
        assertEquals("tests-named", decisionNotes(dir)["refusal-kind"], output)
        assertContains(output, "tests were named for this run")
    }

    @Test
    fun `a repeated request reuses the configuration and still runs every method`(@TempDir dir: File) {
        captured(dir)
        changeBeta(dir)
        launchAlphaTest(dir, "-Pyoriwake.select")
        File(dir, "build/test-results").deleteRecursively()

        val output = launchAlphaTest(dir, "-Pyoriwake.select", "--rerun-tasks")

        assertContains(output, "Reusing configuration cache")
        assertEquals(mapOf("dev.sample.AlphaTest" to setOf("adds", "doubles")), ranMethods(dir), output)
        assertEquals("tests-named", decisionNotes(dir)["refusal-kind"], output)
    }
}
