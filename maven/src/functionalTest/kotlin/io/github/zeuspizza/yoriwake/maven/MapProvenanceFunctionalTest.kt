package io.github.zeuspizza.yoriwake.maven

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** With `-Dyoriwake.trustedMaps`, a Maven selecting run narrows only from a map the list vouches for. */
class MapProvenanceFunctionalTest {

    private val maven = FunctionalMaven.MATRIX[1]

    private fun File.edit(transform: (String) -> String) = writeText(transform(readText()))

    private fun git(project: File, vararg arguments: String): String {
        val process = ProcessBuilder(listOf("git", "-c", "user.name=t", "-c", "user.email=t@t", "-c", "commit.gpgsign=false") + arguments)
            .directory(project).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "git ${arguments.joinToString(" ")} failed:\n$output" }
        return output.trim()
    }

    /** The demo captured, then a Greeter edit committed; returns the project and lib's map. */
    private fun capturedAndEdited(dir: File): Pair<File, File> {
        val project = FunctionalMaven.copy(FunctionalMaven.demo, dir)
        File(project, ".gitignore").writeText("target/\n")
        git(project, "init", "-q")
        git(project, "add", ".")
        git(project, "commit", "-q", "-m", "demo")
        FunctionalMaven.run(maven, project, "verify").succeeded()
        File(project, "lib/src/main/java/dev/demo/lib/Greeter.java").edit { it.replace("\"Hello, \"", "\"Hello,\" + \" \"") }
        git(project, "commit", "-q", "-am", "an edit")
        val map = File(project, ".yoriwake/yoriwake").listFiles().orEmpty().single { it.name.startsWith("dev.demo-lib-") }
        return project to map
    }

    /** Beside the checkout, as a CI job keeps it: a file written into the tree counts as a change. */
    private fun select(list: File) =
        arrayOf("verify", "-pl", "lib", "-Dyoriwake.select", "-Dyoriwake.base=HEAD~1", "-Dyoriwake.trustedMaps=${list.path}")

    @Test
    fun `a map the list vouches for narrows`(@TempDir dir: File) {
        val (project, map) = capturedAndEdited(dir)
        val list = File(dir, "trusted.tsv").apply { writeText("${map.name}\t${File(map, "map-digest").readText().trim().removePrefix("sha256 ")}\n") }

        val output = FunctionalMaven.run(maven, project, *select(list)).succeeded().output

        val selecting = output.substringAfter("[yoriwake] lib surefire:test (default-test) selecting against ", "")
        assertContains(selecting, "in dev.demo.lib.GreeterTest")
        assertFalse("in dev.demo.lib.CounterTest" in selecting, output)
    }

    @Test
    fun `a map the list does not name runs every test and records a new map`(@TempDir dir: File) {
        val (project, _) = capturedAndEdited(dir)
        val list = File(dir, "trusted.tsv").apply { writeText("") }

        val output = FunctionalMaven.run(maven, project, *select(list)).succeeded().output

        assertContains(output, "the trusted-map list does not name the map")
        assertContains(output, "Tests run: 2, Failures: 0")
        assertContains(output, "[yoriwake] lib surefire:test (default-test) map updated: ")
    }

    @Test
    fun `a trusted-map list that cannot be read fails the build naming the flag`(@TempDir dir: File) {
        val (project, _) = capturedAndEdited(dir)

        val list = File(dir, "trusted.tsv")
        val result = FunctionalMaven.run(maven, project, *select(list))

        assertContains(result.output, "-Dyoriwake.trustedMaps=${list.path}")
        assertTrue(result.exitCode != 0, result.output)
    }
}
