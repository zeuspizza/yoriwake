package io.github.zeuspizza.yoriwake.maven

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A recording run on Maven captures each test execution into its own map, and dates it only when it saw every test. */
class CaptureFunctionalTest {

    private val maven = FunctionalMaven.MATRIX[1]

    private fun File.edit(transform: (String) -> String) = writeText(transform(readText()))

    private fun git(project: File, vararg arguments: String): String {
        val process = ProcessBuilder(listOf("git", "-c", "user.name=t", "-c", "user.email=t@t", "-c", "commit.gpgsign=false") + arguments)
            .directory(project).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "git ${arguments.joinToString(" ")} failed:\n$output" }
        return output.trim()
    }

    /** The demo, committed, so a capture has a HEAD to date its map with. */
    private fun committed(dir: File): File = FunctionalMaven.copy(FunctionalMaven.demo, dir).also { project ->
        git(project, "init", "-q")
        git(project, "add", ".")
        git(project, "commit", "-q", "-m", "demo")
    }

    /** The map of `lib`'s Surefire `default-test` execution. */
    private fun libMap(project: File): File? =
        File(project, ".yoriwake/yoriwake").listFiles().orEmpty().singleOrNull { it.name.startsWith("dev.demo-lib-maven-surefire-plugin-default-test-") }

    private fun captureCommit(project: File): String? = libMap(project)?.let { File(it, "capture-commit") }?.takeIf(File::isFile)?.readText()?.trim()

    @ParameterizedTest(name = "Maven {0}")
    @ValueSource(strings = ["3.9.16", "4.0.0-rc-7"])
    fun `a full run captures each module and dates its map`(version: String, @TempDir dir: File) {
        val project = committed(dir)

        val output = FunctionalMaven.run(version, project, "verify").succeeded().output

        assertContains(output, "[yoriwake] lib surefire:test (default-test) map updated: ")
        assertContains(output, "[yoriwake] app surefire:test (default-test) map updated")
        assertEquals(git(project, "rev-parse", "HEAD"), captureCommit(project))
        assertEquals("*\n", File(project, ".yoriwake/.gitignore").readText())
    }

    @Test
    fun `a run with a failing test still decodes and dates the map`(@TempDir dir: File) {
        val project = committed(dir)
        File(project, "lib/src/test/java/dev/demo/lib/GreeterTest.java").edit { it.replace("\"Hello, Ada\"", "\"Goodbye, Ada\"") }
        git(project, "commit", "-q", "-am", "a failing test")

        val output = FunctionalMaven.run(maven, project, "verify").output

        assertContains(output, "Tests run: 2, Failures: 1")
        assertContains(output, "[yoriwake] lib surefire:test (default-test) map updated: ")
        assertEquals(git(project, "rev-parse", "HEAD"), captureCommit(project))
    }

    @Test
    fun `tests named on the command line are captured but do not date the map`(@TempDir dir: File) {
        val project = committed(dir)
        FunctionalMaven.run(maven, project, "verify").succeeded()
        val dated = captureCommit(project)
        File(project, "lib/src/main/java/dev/demo/lib/Counter.java").edit { it.replace("n * 2", "n + n") }
        git(project, "commit", "-q", "-am", "an edit")

        val output = FunctionalMaven.run(maven, project, "verify", "-pl", "lib", "-Dtest=GreeterTest").succeeded().output

        assertContains(output, "[yoriwake] lib surefire:test (default-test): the run was filtered to GreeterTest, so this run's coverage was not kept")
        assertEquals(dated, captureCommit(project))
    }

    @Test
    fun `a capture that stops after a failure leaves the map's stamp as it was`(@TempDir dir: File) {
        val project = committed(dir)
        FunctionalMaven.run(maven, project, "verify").succeeded()
        val dated = captureCommit(project)
        File(project, "lib/src/main/java/dev/demo/lib/Counter.java").edit { it.replace("n * 2", "n + n") }
        git(project, "commit", "-q", "-am", "an edit")

        val output = FunctionalMaven.run(maven, project, "verify", "-pl", "lib", "-Dsurefire.skipAfterFailureCount=1").succeeded().output

        assertContains(output, "the run stops after 1 failure(s)")
        assertEquals(dated, captureCommit(project))
    }

    @Test
    fun `a test that exits the JVM leaves the map undated`(@TempDir dir: File) {
        val project = committed(dir)
        File(project, "lib/src/test/java/dev/demo/lib/ZExitTest.java").writeText(
            """
            package dev.demo.lib;
            class ZExitTest {
                @org.junit.jupiter.api.Test void exits() { System.exit(0); }
            }
            """.trimIndent()
        )
        git(project, "add", ".")
        git(project, "commit", "-q", "-m", "a test that exits")

        val output = FunctionalMaven.run(maven, project, "verify", "-pl", "lib").output

        assertContains(output, "did not finish its run")
        assertNull(captureCommit(project))
    }

    @Test
    fun `skipping the tests leaves the last capture as it was`(@TempDir dir: File) {
        val project = committed(dir)
        FunctionalMaven.run(maven, project, "verify", "-pl", "lib").succeeded()
        val dated = assertNotNull(captureCommit(project))

        val output = FunctionalMaven.run(maven, project, "verify", "-pl", "lib", "-DskipTests").succeeded().output

        assertFalse("[yoriwake] lib" in output, output)
        assertEquals(dated, captureCommit(project))
    }

    @Test
    fun `a module built against a reactor module from a repository captures nothing`(@TempDir dir: File) {
        val project = committed(dir)
        FunctionalMaven.run(maven, project, "install", "-pl", ":demo-maven,:lib", "-DskipTests").succeeded()

        val output = FunctionalMaven.run(maven, project, "verify", "-pl", "app").succeeded().output

        assertContains(output, "[yoriwake] app surefire:test (default-test) declined, reactor-module-from-repository: lib come(s) from a repository, not this build (add -am)")
        assertFalse("app surefire:test (default-test) map updated" in output, output)
    }

    @Test
    fun `mvn clean keeps the map`(@TempDir dir: File) {
        val project = committed(dir)
        FunctionalMaven.run(maven, project, "verify", "-pl", "lib").succeeded()
        val dated = assertNotNull(captureCommit(project))

        FunctionalMaven.run(maven, project, "clean", "-pl", "lib").succeeded()

        assertEquals(dated, captureCommit(project))
    }

    @Test
    fun `a goal typed on its own did not compile the classes and captures nothing`(@TempDir dir: File) {
        val project = committed(dir)
        FunctionalMaven.run(maven, project, "test-compile", "-pl", "lib").succeeded()

        // The declared execution by name: run outside the lifecycle, no compile phase ran this session.
        val output = FunctionalMaven.run(maven, project, "surefire:test@default-test", "-pl", "lib").succeeded().output

        assertContains(output, "[yoriwake] lib surefire:test (default-test): this session did not compile lib's classes and tests, so nothing is captured")
        assertFalse("map updated" in output, output)
        assertTrue(libMap(project) == null || captureCommit(project) == null)
    }
}
