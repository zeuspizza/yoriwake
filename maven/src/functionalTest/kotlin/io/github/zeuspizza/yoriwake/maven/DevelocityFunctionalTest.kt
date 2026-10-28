package io.github.zeuspizza.yoriwake.maven

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Where Develocity's Test Distribution or Predictive Test Selection acts on an execution, yoriwake
 * stands aside. With the real extension loaded (no server reachable) and neither on, it captures.
 */
class DevelocityFunctionalTest {

    private val maven = FunctionalMaven.MATRIX[1]

    private fun File.edit(transform: (String) -> String) = writeText(transform(readText()))

    private fun git(project: File, vararg arguments: String): String {
        val process = ProcessBuilder(listOf("git", "-c", "user.name=t", "-c", "user.email=t@t", "-c", "commit.gpgsign=false") + arguments)
            .directory(project).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "git ${arguments.joinToString(" ")} failed:\n$output" }
        return output.trim()
    }

    /** The demo with Develocity's Maven extension in `.mvn/`, pointed at a server that does not resolve. */
    private fun withDevelocity(dir: File, surefireProperties: String? = null): File =
        FunctionalMaven.copy(FunctionalMaven.demo, dir).also { project ->
            File(project, ".gitignore").writeText("target/\n")
            File(project, ".mvn").mkdirs()
            File(project, ".mvn/extensions.xml").writeText(
                "<extensions><extension><groupId>com.gradle</groupId><artifactId>develocity-maven-extension</artifactId>" +
                    "<version>2.6.1</version></extension></extensions>\n"
            )
            File(project, ".mvn/develocity.xml").writeText(
                "<develocity><server><url>https://develocity.invalid</url></server>" +
                    "<buildScan><publishing><onlyIf>false</onlyIf></publishing></buildScan></develocity>\n"
            )
            surefireProperties?.let { properties ->
                File(project, "pom.xml").edit {
                    it.replace(
                        "<plugin><artifactId>maven-surefire-plugin</artifactId><version>3.5.6</version></plugin>",
                        "<plugin><artifactId>maven-surefire-plugin</artifactId><version>3.5.6</version>" +
                            "<configuration><properties>$properties</properties></configuration></plugin>",
                    )
                }
            }
            git(project, "init", "-q")
            git(project, "add", ".")
            git(project, "commit", "-q", "-m", "demo with Develocity")
        }

    @Test
    fun `Predictive Test Selection on an execution declines it`(@TempDir dir: File) {
        val project = withDevelocity(dir, "<predictiveSelection><enabled>true</enabled></predictiveSelection>")

        val output = FunctionalMaven.run(maven, project, "verify", "-pl", "lib", "-Ddevelocity.testing.fallbackToRegularExecutionOnMissingPrerequisites=true").output

        assertContains(output, "[yoriwake] lib surefire:test (default-test) declined, develocity-test-selection: ")
        assertFalse("lib surefire:test (default-test) map updated" in output, output)
    }

    @Test
    fun `Test Distribution on an execution declines it`(@TempDir dir: File) {
        val project = withDevelocity(dir, "<distribution><enabled>true</enabled></distribution>")

        val output = FunctionalMaven.run(maven, project, "verify", "-pl", "lib", "-Ddevelocity.testing.fallbackToRegularExecutionOnMissingPrerequisites=true").output

        assertContains(output, "[yoriwake] lib surefire:test (default-test) declined, develocity-test-distribution: ")
    }

    @Test
    fun `pts enabled on the command line declines every execution`(@TempDir dir: File) {
        val project = withDevelocity(dir)

        val output = FunctionalMaven.run(maven, project, "verify", "-pl", "lib", "-Dpts.enabled=true", "-Ddevelocity.testing.fallbackToRegularExecutionOnMissingPrerequisites=true").output

        assertContains(output, "[yoriwake] lib surefire:test (default-test) declined, develocity-test-selection: ")
    }

    @Test
    fun `a setting that cannot be read as true or false declines`(@TempDir dir: File) {
        val project = withDevelocity(dir, "<predictiveSelection><enabled>\${nobody.sets.this}</enabled></predictiveSelection>")

        val output = FunctionalMaven.run(maven, project, "verify", "-pl", "lib", "-Ddevelocity.testing.fallbackToRegularExecutionOnMissingPrerequisites=true").output

        assertContains(output, "[yoriwake] lib surefire:test (default-test) declined, develocity-undetermined: ")
    }

    @Test
    fun `explain reports a declined execution as running everything`(@TempDir dir: File) {
        val project = withDevelocity(dir, "<predictiveSelection><enabled>true</enabled></predictiveSelection>")

        val output = FunctionalMaven.run(maven, project, "test-compile", "yoriwake:explain", "-pl", "lib").output

        assertContains(output, "[yoriwake] lib surefire:test (default-test) would run everything: declined, develocity-test-selection")
    }

    @Test
    fun `with Develocity loaded and neither feature on the execution captures`(@TempDir dir: File) {
        val project = withDevelocity(dir)

        val output = FunctionalMaven.run(maven, project, "verify", "-pl", "lib").succeeded().output

        assertFalse("declined, develocity" in output, output)
        assertContains(output, "[yoriwake] lib surefire:test (default-test) map updated: ")
    }

    @Test
    fun `Develocity's workspace id written during the capture does not force the next selecting run`(@TempDir dir: File) {
        val project = withDevelocity(dir)
        FunctionalMaven.run(maven, project, "verify", "-pl", "lib").succeeded()
        val id = File(project, ".mvn/.develocity/develocity-workspace-id")
        assertTrue(id.isFile, "Develocity wrote no workspace id")
        assertEquals(listOf("develocity-workspace-id"), id.parentFile.list()!!.sorted())
        assertEquals("?? .mvn/.develocity/develocity-workspace-id", git(project, "status", "--porcelain", "--untracked-files=all", ".mvn"))
        File(project, "lib/src/main/java/dev/demo/lib/Greeter.java").edit { it.replace("\"Hello, \"", "\"Hello,\" + \" \"") }
        git(project, "commit", "-q", "-am", "an edit")

        val output = FunctionalMaven.run(maven, project, "verify", "-pl", "lib", "-Dyoriwake.select", "-Dyoriwake.base=HEAD~1")
            .succeeded().output

        val selecting = output.substringAfter("[yoriwake] lib surefire:test (default-test) selecting against ", "")
        assertContains(selecting.substringBefore("\n"), "0 paths coverage cannot see")
        assertContains(selecting, "in dev.demo.lib.GreeterTest")
        assertFalse("in dev.demo.lib.CounterTest" in selecting, output)
    }
}
