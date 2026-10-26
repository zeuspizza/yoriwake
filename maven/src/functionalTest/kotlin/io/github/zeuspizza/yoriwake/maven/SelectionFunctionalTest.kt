package io.github.zeuspizza.yoriwake.maven

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A selecting run on Maven (`-Dyoriwake.select`) runs what a change reaches, and refuses where Gradle refuses. */
class SelectionFunctionalTest {

    private val maven = FunctionalMaven.MATRIX[1]

    private fun File.edit(transform: (String) -> String) = writeText(transform(readText()))

    private fun git(project: File, vararg arguments: String): String {
        val process = ProcessBuilder(listOf("git", "-c", "user.name=t", "-c", "user.email=t@t", "-c", "commit.gpgsign=false") + arguments)
            .directory(project).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "git ${arguments.joinToString(" ")} failed:\n$output" }
        return output.trim()
    }

    /** The demo, committed as a Maven project is, with its build output ignored. */
    private fun committed(dir: File): File = FunctionalMaven.copy(FunctionalMaven.demo, dir).also { project ->
        File(project, ".gitignore").writeText("target/\n")
        git(project, "init", "-q")
        git(project, "add", ".")
        git(project, "commit", "-q", "-m", "demo")
    }

    /** The demo, committed and captured, so a selecting run has a dated map to select from. */
    private fun captured(dir: File): File = committed(dir).also { project ->
        FunctionalMaven.run(maven, project, "verify").succeeded()
    }

    private fun editCounter(project: File) {
        File(project, "lib/src/main/java/dev/demo/lib/Counter.java").edit { it.replace("n * 2", "n + n") }
        git(project, "commit", "-q", "-am", "an edit")
    }

    @Test
    fun `a change to one class runs the tests that reach it`(@TempDir dir: File) {
        val project = captured(dir)
        // Greeter, which only the second test class loads: a class loaded earlier in the shared test
        // JVM would keep every test that ran after it.
        File(project, "lib/src/main/java/dev/demo/lib/Greeter.java").edit { it.replace("\"Hello, \"", "\"Hello,\" + \" \"") }
        git(project, "commit", "-q", "-am", "an edit")

        val output = FunctionalMaven.run(maven, project, "verify", "-pl", "lib", "-Dyoriwake.select", "-Dyoriwake.base=HEAD~1")
            .succeeded().output

        val selecting = output.substringAfter("[yoriwake] lib surefire:test (default-test) selecting against ", "")
        assertContains(selecting, "in dev.demo.lib.GreeterTest")
        assertFalse("in dev.demo.lib.CounterTest" in selecting, output)
    }

    @Test
    fun `an observing run runs every test and reports what selection would have done`(@TempDir dir: File) {
        val project = captured(dir)
        File(project, "lib/src/main/java/dev/demo/lib/Greeter.java").edit { it.replace("\"Hello, \"", "\"Hello,\" + \" \"") }
        git(project, "commit", "-q", "-am", "an edit")

        val output = FunctionalMaven.run(maven, project, "verify", "-pl", "lib", "-Dyoriwake.observe", "-Dyoriwake.base=HEAD~1")
            .succeeded().output

        assertContains(output, "Tests run: 2, Failures: 0")
        assertContains(output, "[yoriwake] lib surefire:test (default-test) observed: selection would have narrowed; no test failed; 0 would-be misses; 1 tests would have been skipped")
        val map = File(project, ".yoriwake/yoriwake").listFiles().orEmpty().single { it.name.startsWith("dev.demo-lib-") }
        assertTrue(File(map, "observation.json").isFile, "no observation.json in $map")
    }

    /** The plugin's `<configuration>` in the root POM, given [xml]; committed. */
    private fun configured(project: File, xml: String) {
        File(project, "pom.xml").edit {
            it.replace("<extensions>true</extensions>\n      </plugin>", "<extensions>true</extensions>\n        <configuration>$xml</configuration>\n      </plugin>")
        }
        git(project, "commit", "-q", "-am", "configure yoriwake")
    }

    @Test
    fun `on a branch fullRunBranches lists a selecting run runs everything and records the map`(@TempDir dir: File) {
        val project = captured(dir)
        git(project, "branch", "-M", "main")
        configured(project, "<fullRunBranches><branch>main</branch></fullRunBranches>")

        val output = FunctionalMaven.run(maven, project, "verify", "-pl", "lib", "-Dyoriwake.select", "-Dyoriwake.base=HEAD~1")
            .succeeded().output

        assertContains(output, "on branch main, and fullRunBranches lists `main`, so selection is declined")
        assertContains(output, "Tests run: 2, Failures: 0")
        assertContains(output, "[yoriwake] lib surefire:test (default-test) map updated: ")
    }

    @Test
    fun `more changed paths than largeChangeThreshold run everything`(@TempDir dir: File) {
        val project = captured(dir)
        configured(project, "<largeChangeThreshold>1</largeChangeThreshold>")
        File(project, "lib/src/main/java/dev/demo/lib/Greeter.java").edit { it.replace("\"Hello, \"", "\"Hello,\" + \" \"") }
        File(project, "lib/src/main/java/dev/demo/lib/Counter.java").edit { it.replace("n * 2", "n + n") }
        git(project, "commit", "-q", "-am", "two edits")

        val output = FunctionalMaven.run(maven, project, "verify", "-pl", "lib", "-Dyoriwake.select", "-Dyoriwake.base=HEAD~1")
            .succeeded().output

        assertContains(output, "Analysing a change this size costs more than it saves")
        assertContains(output, "Tests run: 2, Failures: 0")
    }

    @Test
    fun `a largeChangeThreshold below one fails the build`(@TempDir dir: File) {
        val project = committed(dir)
        configured(project, "<largeChangeThreshold>0</largeChangeThreshold>")

        val result = FunctionalMaven.run(maven, project, "verify", "-pl", "lib")

        assertContains(result.output, "largeChangeThreshold is 0; it counts changed paths, so it must be 1 or more")
        assertTrue(result.exitCode != 0, result.output)
    }

    @Test
    fun `an empty change set runs every test`(@TempDir dir: File) {
        val project = captured(dir)

        val output = FunctionalMaven.run(maven, project, "verify", "-pl", "lib", "-Dyoriwake.select", "-Dyoriwake.base=HEAD")
            .succeeded().output

        assertContains(output, "[yoriwake] running everything: the change set is empty")
        assertContains(output, "Tests run: 2, Failures: 0")
    }

    @Test
    fun `with no map a selecting run runs everything and records it`(@TempDir dir: File) {
        val project = committed(dir)

        val output = FunctionalMaven.run(maven, project, "verify", "-pl", "lib", "-Dyoriwake.select", "-Dyoriwake.base=HEAD")
            .succeeded().output

        assertContains(output, "Tests run: 2, Failures: 0")
        assertContains(output, "[yoriwake] lib surefire:test (default-test) map updated: ")
    }

    @Test
    fun `a full run asked for on the command line runs everything and records the map`(@TempDir dir: File) {
        val project = captured(dir)
        editCounter(project)

        val output = FunctionalMaven.run(
            maven, project, "verify", "-pl", "lib", "-Dyoriwake.select", "-Dyoriwake.fullRun", "-Dyoriwake.base=HEAD~1",
        ).succeeded().output

        assertContains(output, "a full run was requested with -Dyoriwake.fullRun, so selection is declined")
        assertContains(output, "Tests run: 2, Failures: 0")
        assertContains(output, "[yoriwake] lib surefire:test (default-test) map updated: ")
    }

    @Test
    fun `a map captured at a commit git cannot relate refuses and captures`(@TempDir dir: File) {
        val project = captured(dir)
        File(project, ".yoriwake/yoriwake").listFiles().orEmpty()
            .filter { it.name.startsWith("dev.demo-lib-") }
            .forEach { File(it, "capture-commit").writeText("0123456789abcdef0123456789abcdef01234567\n") }

        val output = FunctionalMaven.run(maven, project, "verify", "-pl", "lib", "-Dyoriwake.select", "-Dyoriwake.base=HEAD")
            .succeeded().output

        assertContains(output, "captured at a commit git cannot relate to")
        assertContains(output, "Tests run: 2, Failures: 0")
    }
}
