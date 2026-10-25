package io.github.zeuspizza.yoriwake.maven

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** How Surefire forks and schedules tests, and what each shape lets a recording run date. */
class ForkingFunctionalTest {

    private val maven = FunctionalMaven.MATRIX[1]

    private fun File.edit(transform: (String) -> String) = writeText(transform(readText()))

    private fun git(project: File, vararg arguments: String): String {
        val process = ProcessBuilder(listOf("git", "-c", "user.name=t", "-c", "user.email=t@t", "-c", "commit.gpgsign=false") + arguments)
            .directory(project).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "git ${arguments.joinToString(" ")} failed:\n$output" }
        return output.trim()
    }

    /** The demo with a third test class in `lib`, Surefire configured as [surefire], committed. */
    private fun committed(dir: File, surefire: String = ""): File = FunctionalMaven.copy(FunctionalMaven.demo, dir).also { project ->
        File(project, "lib/src/test/java/dev/demo/lib/ThirdTest.java").writeText(
            """
            package dev.demo.lib;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            class ThirdTest {
                @org.junit.jupiter.api.Test void third() { assertEquals(6, new Counter().twice(3)); }
            }
            """.trimIndent()
        )
        if (surefire.isNotEmpty()) {
            File(project, "lib/pom.xml").edit {
                it.replace(
                    "<artifactId>lib</artifactId>",
                    "<artifactId>lib</artifactId>\n  <build><plugins><plugin><artifactId>maven-surefire-plugin</artifactId>" +
                        "<configuration>$surefire</configuration></plugin></plugins></build>",
                )
            }
        }
        git(project, "init", "-q")
        git(project, "add", ".")
        git(project, "commit", "-q", "-m", "demo")
    }

    private fun libMap(project: File): File =
        File(project, ".yoriwake/yoriwake").listFiles().orEmpty().single { it.name.startsWith("dev.demo-lib-maven-surefire-plugin-default-test-") }

    private fun captureCommit(project: File): String? = File(libMap(project), "capture-commit").takeIf(File::isFile)?.readText()?.trim()

    private fun recorded(project: File, vararg arguments: String): String {
        val output = FunctionalMaven.run(maven, project, "verify", "-pl", "lib", *arguments).succeeded().output
        assertFalse("already contains records" in output, output)
        assertEquals(git(project, "rev-parse", "HEAD"), captureCommit(project), output)
        return output
    }

    @Test
    fun `parallel execution enabled for the JUnit Platform is declined and captures nothing`(@TempDir dir: File) {
        val project = committed(
            dir,
            "<properties><configurationParameters>junit.jupiter.execution.parallel.enabled=true</configurationParameters></properties>",
        )

        val output = FunctionalMaven.run(maven, project, "verify", "-pl", "lib").succeeded().output

        assertContains(output, "[yoriwake] lib surefire:test (default-test) declined, in-jvm-parallelism")
        assertTrue(File(project, ".yoriwake/yoriwake").listFiles().orEmpty().none { File(it, "capture-commit").isFile })
    }

    @Test
    fun `parallel execution enabled on the command line is declined as well`(@TempDir dir: File) {
        // Surefire hands the fork the command line's user properties as system properties.
        val project = committed(dir)

        val output = FunctionalMaven.run(maven, project, "verify", "-pl", "lib", "-Djunit.jupiter.execution.parallel.enabled=true").succeeded().output

        assertContains(output, "[yoriwake] lib surefire:test (default-test) declined, in-jvm-parallelism")
    }

    @Test
    fun `parallel execution enabled in junit-platform properties is declined as well`(@TempDir dir: File) {
        val project = committed(dir)
        File(project, "lib/src/test/resources").mkdirs()
        File(project, "lib/src/test/resources/junit-platform.properties").writeText("junit.jupiter.execution.parallel.enabled=true\n")
        git(project, "add", ".")
        git(project, "commit", "-q", "-m", "parallel")

        val output = FunctionalMaven.run(maven, project, "verify", "-pl", "lib").succeeded().output

        assertContains(output, "[yoriwake] lib surefire:test (default-test) declined, in-jvm-parallelism")
    }

    @Test
    fun `Surefire's own parallel setting does not reach the Platform, and the run dates its map`(@TempDir dir: File) {
        val project = committed(dir, "<parallel>classes</parallel><threadCount>4</threadCount>")

        val output = recorded(project)

        assertContains(output, "Surefire's parallel=classes applies to its JUnit 4.7 and TestNG providers only")
    }

    @Test
    fun `an argLine that drops JaCoCo's agent captures nothing`(@TempDir dir: File) {
        val project = committed(dir, "<argLine>-Xmx256m</argLine>")

        val output = FunctionalMaven.run(maven, project, "verify", "-pl", "lib").succeeded().output

        assertContains(output, "[yoriwake] lib surefire:test (default-test) declined, no-jacoco-plugin: the JaCoCo agent does not reach")
    }

    @Test
    fun `an argLine that keeps JaCoCo's agent beside its own options captures`(@TempDir dir: File) {
        val project = committed(dir, "<argLine>@{argLine} -Xmx256m</argLine>")

        recorded(project)
    }

    @Test
    fun `two forks record into two workers`(@TempDir dir: File) {
        val project = committed(dir, "<forkCount>2</forkCount><reuseForks>false</reuseForks>")

        recorded(project)
    }

    @Test
    fun `reused forks running several classes record each plan apart and date the map`(@TempDir dir: File) {
        val project = committed(dir, "<forkCount>2</forkCount><reuseForks>true</reuseForks>")

        recorded(project)
    }

    @Test
    fun `isolated capture runs a JVM per class`(@TempDir dir: File) {
        val project = committed(dir)

        recorded(project, "-Dyoriwake.isolatedCapture")

        val modes = File(libMap(project), "jvm-mode.tsv").readLines().filter(String::isNotBlank)
        assertTrue(modes.isNotEmpty() && modes.all { "isolated" in it }, modes.joinToString("\n"))
    }

    @Test
    fun `a capture records the JVM's environment and the build's files on its classpath`(@TempDir dir: File) {
        val project = committed(dir)

        recorded(project)

        assertTrue(File(libMap(project), "environment.tsv").let { it.isFile && it.length() > 0 }, libMap(project).list().orEmpty().joinToString())
        assertTrue(File(libMap(project), "resource-digests").isFile, libMap(project).list().orEmpty().joinToString())
    }

    @Test
    fun `a reactor under a path with a space captures and dates`(@TempDir dir: File) {
        val project = committed(File(dir, "with space"))

        recorded(project)
    }

    @Test
    fun `a multithreaded reactor build dates every module`(@TempDir dir: File) {
        val project = committed(dir)

        val output = FunctionalMaven.run(maven, project, "verify", "-T", "2").succeeded().output

        listOf("lib", "app", "kotlin", "modp").forEach { assertContains(output, "[yoriwake] $it surefire:test (default-test) map updated") }
        assertNull(Regex("could not update the map").find(output)?.value)
    }
}
