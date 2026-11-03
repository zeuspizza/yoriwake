package io.github.zeuspizza.yoriwake.maven

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.security.MessageDigest
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * `-Dyoriwake.recordSequentially` on Maven: a recording run of a suite the JUnit Platform runs in
 * parallel inside one JVM turns the engines' parallel switches off and records the map; any other
 * run keeps the suite's scheduling, selects as usual, and records nothing.
 */
class SequentialRecordingFunctionalTest {

    private val maven = FunctionalMaven.MATRIX[1]

    private val parallel = "<properties><configurationParameters>junit.jupiter.execution.parallel.enabled=true\n" +
        "junit.jupiter.execution.parallel.mode.default=concurrent</configurationParameters></properties>"

    private fun File.edit(transform: (String) -> String) = writeText(transform(readText()))

    private fun git(project: File, vararg arguments: String): String {
        val process = ProcessBuilder(listOf("git", "-c", "user.name=t", "-c", "user.email=t@t", "-c", "commit.gpgsign=false") + arguments)
            .directory(project).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "git ${arguments.joinToString(" ")} failed:\n$output" }
        return output.trim()
    }

    /** The demo, `lib`'s Surefire configured as [surefire] and the yoriwake plugin as [yoriwake], committed. */
    private fun committed(dir: File, surefire: String = parallel, yoriwake: String = ""): File =
        FunctionalMaven.copy(FunctionalMaven.demo, dir).also { project ->
            if (surefire.isNotEmpty()) {
                File(project, "lib/pom.xml").edit {
                    it.replace(
                        "<artifactId>lib</artifactId>",
                        "<artifactId>lib</artifactId>\n  <build><plugins><plugin><artifactId>maven-surefire-plugin</artifactId>" +
                            "<configuration>$surefire</configuration></plugin></plugins></build>",
                    )
                }
            }
            if (yoriwake.isNotEmpty()) {
                File(project, "pom.xml").edit {
                    it.replace("<extensions>true</extensions>", "<extensions>true</extensions><configuration>$yoriwake</configuration>")
                }
            }
            File(project, ".gitignore").writeText("target/\n")
            git(project, "init", "-q")
            git(project, "add", ".")
            git(project, "commit", "-q", "-m", "demo")
        }

    private fun verify(project: File, vararg arguments: String): String =
        FunctionalMaven.run(maven, project, "verify", "-pl", "lib", *arguments).succeeded().output

    private fun libMap(project: File): File =
        File(project, ".yoriwake/yoriwake").listFiles().orEmpty().single { it.name.startsWith("dev.demo-lib-maven-surefire-plugin-default-test-") }

    private fun captureCommit(project: File): String? =
        File(libMap(project), "capture-commit").takeIf(File::isFile)?.readText()?.trim()

    /** The decision record's notes, as `#!key<TAB>value` lines. */
    private fun notes(project: File): Map<String, String> = File(libMap(project), "decisions.tsv").readLines()
        .filter { it.startsWith("#!") }
        .associate { line -> line.removePrefix("#!").split("\t", limit = 2).let { it[0] to it.getOrElse(1) { "" } } }

    /** The map's own files, by digest: what a capture writes and a run that records nothing leaves alone. */
    private fun mapDigest(project: File): Map<String, String> =
        listOf("capture-commit", "coverage.tsv", "positions.tsv", "environment.tsv", "environment-fingerprints.tsv")
            .associateWith { name ->
                File(libMap(project), name).takeIf(File::isFile)?.readBytes()
                    ?.let { MessageDigest.getInstance("SHA-256").digest(it).joinToString("") { b -> "%02x".format(b) } }
                    .orEmpty()
            }

    /** The run dated the map at HEAD and recorded both of `lib`'s test classes. */
    private fun assertRecorded(project: File, output: String) {
        assertFalse("in-jvm-parallelism" in output, output)
        assertEquals(git(project, "rev-parse", "HEAD"), captureCommit(project), output)
        val coverage = File(libMap(project), "coverage.tsv").readText()
        assertContains(coverage, "GreeterTest")
        assertContains(coverage, "CounterTest")
    }

    private fun editGreeter(project: File) {
        File(project, "lib/src/main/java/dev/demo/lib/Greeter.java").edit { it.replace("\"Hello, \"", "\"Hello,\" + \" \"") }
        git(project, "commit", "-q", "-am", "an edit")
    }

    @Test
    fun `a flagged recording run of a parallel suite records it one test at a time`(@TempDir dir: File) {
        val project = committed(dir)

        val output = verify(project, "-Dyoriwake.recordSequentially")

        assertContains(output, "recording one test at a time")
        assertRecorded(project, output)
        assertEquals("recorded-sequentially", notes(project)["parallelism"], output)
    }

    @Test
    fun `the POM setting alone records sequentially, and the command line turns it off`(@TempDir dir: File) {
        val project = committed(dir, yoriwake = "<recordSequentially>true</recordSequentially>")

        assertRecorded(project, verify(project))
        assertContains(
            verify(project, "-Dyoriwake.recordSequentially=false"),
            "[yoriwake] lib surefire:test (default-test) declined, in-jvm-parallelism",
        )
    }

    @Test
    fun `parallelism turned on on the command line is turned off too`(@TempDir dir: File) {
        // Surefire promotes the user property to a system property, which the launcher's own
        // configuration parameters outrank.
        val project = committed(dir, surefire = "")

        val output = verify(
            project, "-Djunit.jupiter.execution.parallel.enabled=true", "-Djunit.jupiter.execution.parallel.mode.default=concurrent",
            "-Dyoriwake.recordSequentially",
        )

        assertRecorded(project, output)
    }

    @Test
    fun `a flagged selecting run narrows with the suite's own scheduling and captures nothing`(@TempDir dir: File) {
        val project = committed(dir)
        verify(project, "-Dyoriwake.recordSequentially")
        editGreeter(project)
        val before = mapDigest(project)

        val output = verify(project, "-Dyoriwake.select", "-Dyoriwake.base=HEAD~1", "-Dyoriwake.recordSequentially")

        assertContains(output, "tests run in parallel as configured")
        val selected = output.substringAfter("[yoriwake] lib surefire:test (default-test) selecting against ", "")
        assertContains(selected, "in dev.demo.lib.GreeterTest")
        assertFalse("in dev.demo.lib.CounterTest" in selected, output)
        assertFalse("environment-changed" in output, output)
        assertEquals("kept-not-captured", notes(project)["parallelism"], output)
        assertEquals(before, mapDigest(project), output)
        val audit = FunctionalMaven.run(maven, project, "yoriwake:audit", "-pl", "lib").succeeded().output
        assertFalse("in-jvm-parallelism" in audit, audit)
    }

    @Test
    fun `a flagged selecting run forced to run everything records nothing`(@TempDir dir: File) {
        val project = committed(dir)
        git(project, "branch", "-M", "main")
        verify(project, "-Dyoriwake.recordSequentially")
        File(project, "pom.xml").edit {
            it.replace("<extensions>true</extensions>", "<extensions>true</extensions><configuration><fullRunBranches><branch>main</branch></fullRunBranches></configuration>")
        }
        git(project, "commit", "-q", "-am", "full runs on main")
        val before = mapDigest(project)

        val output = verify(project, "-Dyoriwake.select", "-Dyoriwake.base=HEAD~1", "-Dyoriwake.recordSequentially")

        assertContains(output, "Tests run: 2, Failures: 0")
        assertContains(output, "tests run in parallel as configured")
        assertEquals(before, mapDigest(project), output)
    }

    @Test
    fun `an audit after an unflagged parallel run names the setting`(@TempDir dir: File) {
        val project = committed(dir)
        assertContains(verify(project), "declined, in-jvm-parallelism")

        val output = FunctionalMaven.run(maven, project, "yoriwake:audit", "-pl", "lib").succeeded().output

        assertContains(output, "-Dyoriwake.recordSequentially")
        assertContains(output, "JUnit Platform only")
    }
}
