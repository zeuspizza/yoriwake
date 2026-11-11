package io.github.zeuspizza.yoriwake.maven

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `-Dyoriwake.narrowLineOnlyChanges` on Maven: a comment edit to a `lib` class runs only the tests
 * that could have seen a line number or read the file, per Surefire execution.
 */
class LineOnlyChangeFunctionalTest {

    private val maven = FunctionalMaven.MATRIX[1]

    private fun git(project: File, vararg arguments: String): String {
        val process = ProcessBuilder(listOf("git", "-c", "user.name=t", "-c", "user.email=t@t", "-c", "commit.gpgsign=false") + arguments)
            .directory(project).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "git ${arguments.joinToString(" ")} failed:\n$output" }
        return output.trim()
    }

    /** The demo captured, then a comment above `Greeter.greet` committed. */
    private fun edited(dir: File): File = FunctionalMaven.copy(FunctionalMaven.demo, dir).also { project ->
        File(project, ".gitignore").writeText("target/\n")
        git(project, "init", "-q")
        git(project, "add", ".")
        git(project, "commit", "-q", "-m", "demo")
        FunctionalMaven.run(maven, project, "verify", "-pl", "lib").succeeded()
        File(project, "lib/src/main/java/dev/demo/lib/Greeter.java").apply {
            writeText(readText().replace("    public String greet", "    // Greets by name.\n    public String greet"))
        }
        git(project, "commit", "-q", "-am", "a comment")
    }

    private fun mapDir(project: File) =
        File(project, ".yoriwake/yoriwake").listFiles().orEmpty().single { it.name.startsWith("dev.demo-lib-") }

    /** Each test's reason column, and the run notes, across every test JVM's decision record. */
    private fun records(project: File): List<String> =
        mapDir(project).listFiles { file -> file.name.startsWith("decisions.tsv") }.orEmpty().flatMap { it.readLines() }

    /** The reason every record of this run gives [test]: one, however many JVMs recorded it. */
    private fun reason(project: File, test: String) =
        records(project).filter { it.isNotBlank() && !it.startsWith("#") }.map { it.split("\t") }
            .filter { test in it[0] }.map { it[2] }.toSet().single()

    private val select = arrayOf("verify", "-pl", "lib", "-Dyoriwake.select", "-Dyoriwake.base=HEAD~1")

    @Test
    fun `with the flag, a comment edit runs no test that only covers the class`(@TempDir dir: File) {
        val project = edited(dir)

        val output = FunctionalMaven.run(maven, project, *select, "-Dyoriwake.narrowLineOnlyChanges").succeeded().output

        assertEquals("SKIPPED", reason(project, "GreeterTest"), output)
        assertTrue(records(project).any { it == "#!lines-only\t1" }, records(project).toString())
        assertContains(output, "changed only in line information")
    }

    @Test
    fun `without the flag, the same edit runs the tests that cover the class`(@TempDir dir: File) {
        val project = edited(dir)

        val output = FunctionalMaven.run(maven, project, *select).succeeded().output

        assertEquals("REACHES_CHANGE", reason(project, "GreeterTest"), output)
    }

    @Test
    fun `an execution with nothing compiled this session runs every test`(@TempDir dir: File) {
        val project = edited(dir)

        val output = FunctionalMaven.run(
            maven, project, "surefire:test", "-pl", "lib", "-Dyoriwake.select", "-Dyoriwake.base=HEAD~1",
            "-Dyoriwake.narrowLineOnlyChanges",
        ).succeeded().output

        assertContains(output, "this session did not compile")
        assertContains(output, "Tests run: 2, Failures: 0")
    }

    @Test
    fun `a value that is no boolean is named, and the execution runs every test`(@TempDir dir: File) {
        val project = edited(dir)

        val output = FunctionalMaven.run(maven, project, *select, "-Dyoriwake.narrowLineOnlyChanges=yes").succeeded().output

        assertContains(output, "-Dyoriwake.narrowLineOnlyChanges=yes is not a boolean")
        assertContains(output, "Tests run: 2, Failures: 0")
    }
}
