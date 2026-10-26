package io.github.zeuspizza.yoriwake.maven

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `mvn yoriwake:audit` reads each execution's map and says whether selection would pay off. */
class AuditAndExplainFunctionalTest {

    private val maven = FunctionalMaven.MATRIX[1]

    private fun git(project: File, vararg arguments: String): String {
        val process = ProcessBuilder(listOf("git", "-c", "user.name=t", "-c", "user.email=t@t", "-c", "commit.gpgsign=false") + arguments)
            .directory(project).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "git ${arguments.joinToString(" ")} failed:\n$output" }
        return output.trim()
    }

    private fun committed(dir: File): File = FunctionalMaven.copy(FunctionalMaven.demo, dir).also { project ->
        File(project, ".gitignore").writeText("target/\n")
        git(project, "init", "-q")
        git(project, "add", ".")
        git(project, "commit", "-q", "-m", "demo")
    }

    @Test
    fun `an audit after a capture reports each execution and writes its file`(@TempDir dir: File) {
        val project = committed(dir)
        FunctionalMaven.run(maven, project, "verify").succeeded()

        val output = FunctionalMaven.run(maven, project, "yoriwake:audit").succeeded().output

        assertContains(output, "[yoriwake] lib surefire:test (default-test): ")
        assertContains(output, "[yoriwake] app surefire:test (default-test): ")
        val map = File(project, ".yoriwake/yoriwake").listFiles().orEmpty().single { it.name.startsWith("dev.demo-lib-") }
        assertTrue(File(map, "audit.json").isFile, "no audit.json in $map")
    }

    private fun File.edit(transform: (String) -> String) = writeText(transform(readText()))

    @Test
    fun `explain says what a selecting run would run and writes its file`(@TempDir dir: File) {
        val project = committed(dir)
        FunctionalMaven.run(maven, project, "verify").succeeded()
        File(project, "lib/src/main/java/dev/demo/lib/Greeter.java").edit { it.replace("\"Hello, \"", "\"Hello,\" + \" \"") }
        git(project, "commit", "-q", "-am", "an edit")

        val output = FunctionalMaven.run(maven, project, "test-compile", "yoriwake:explain", "-pl", "lib", "-Dyoriwake.base=HEAD~1")
            .succeeded().output

        assertContains(output, "[yoriwake] lib surefire:test (default-test) would run 1 of 2 known tests")
        val map = File(project, ".yoriwake/yoriwake").listFiles().orEmpty().single { it.name.startsWith("dev.demo-lib-") }
        assertTrue(File(map, "explain.json").isFile, "no explain.json in $map")
        assertFalse(File(map, "change-set").exists() || File(map, "selection-start.pending").exists(), "explain wrote into the map")
    }

    @Test
    fun `explain with no map says the run would run everything`(@TempDir dir: File) {
        val project = committed(dir)

        val output = FunctionalMaven.run(maven, project, "test-compile", "yoriwake:explain", "-pl", "lib", "-Dyoriwake.base=HEAD")
            .succeeded().output

        assertContains(output, "[yoriwake] lib surefire:test (default-test) would run everything")
    }

    @Test
    fun `an audit with no map says so and fails nothing`(@TempDir dir: File) {
        val project = committed(dir)

        val output = FunctionalMaven.run(maven, project, "yoriwake:audit", "-Dyoriwake.execution=default-test").succeeded().output

        assertContains(output, "[yoriwake] lib surefire:test (default-test): ")
    }
}
