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
        // The run recorded its framework, and no script here measures Maven's toll.
        val lib = output.substringAfter("[yoriwake] lib surefire:test (default-test): ").substringBefore("[yoriwake] app surefire:test")
        assertContains(lib, "UNMEASURED")
        assertFalse("framework-unknown" in lib, lib)
        assertFalse("measure-toll.sh" in output, output)
    }

    /** What yoriwake printed, without Maven's own lines. */
    private fun yoriwakeLines(output: String) = output.lines().filter { "[yoriwake]" in it }.joinToString("\n")

    /** No remedy a Maven user cannot follow: a Gradle setting, flag or init script. */
    private fun assertNoGradleRemedy(output: String) {
        val printed = yoriwakeLines(output)
        for (gradle in listOf(
            "framework-unknown", "narrowPlainRunners", "-Pyoriwake", "init.gradle", "`jacoco` plugin", "jacoco extension",
            "recordSequentially",
        )) {
            assertFalse(gradle in printed, printed)
        }
    }

    @Test
    fun `an execution on JUnit 4 alone audits as off the Platform with a Maven remedy`(@TempDir dir: File) {
        val project = committed(dir)
        File(project, "pom.xml").edit {
            it.replace(
                Regex("""<groupId>org\.junit\.jupiter</groupId>\s*<artifactId>junit-jupiter</artifactId>"""),
                "<groupId>junit</groupId><artifactId>junit</artifactId><version>4.13.2</version>",
            )
        }
        File(project, "lib/src/test/java/dev/demo/lib").listFiles().orEmpty().forEach { test ->
            test.edit {
                it.replace("org.junit.jupiter.api.Assertions.assertEquals", "org.junit.Assert.assertEquals")
                    .replace("org.junit.jupiter.api.Test", "org.junit.Test")
                    .replace("\nclass ", "\npublic class ")
                    .replace("    void ", "    public void ")
            }
        }
        git(project, "commit", "-q", "-am", "JUnit 4 alone")
        FunctionalMaven.run(maven, project, "verify", "-pl", "lib").succeeded()

        val output = FunctionalMaven.run(maven, project, "yoriwake:audit", "-pl", "lib").succeeded().output

        assertContains(output, "[not-on-junit-platform] this task runs on JUnit 4, off the Platform")
        assertContains(output, "org.junit.vintage:junit-vintage-engine")
        assertNoGradleRemedy(output)
    }

    @Test
    fun `a legacy provider named in the POM audits with its decline's remedy`(@TempDir dir: File) {
        val project = committed(dir)
        File(project, "lib/pom.xml").edit {
            it.replace(
                "<artifactId>lib</artifactId>",
                "<artifactId>lib</artifactId>\n  <build><plugins><plugin><artifactId>maven-surefire-plugin</artifactId><dependencies>" +
                    "<dependency><groupId>org.apache.maven.surefire</groupId><artifactId>surefire-junit4</artifactId>" +
                    "<version>3.5.6</version></dependency></dependencies></plugin></plugins></build>",
            )
        }

        val output = FunctionalMaven.run(maven, project, "yoriwake:audit", "-pl", "lib").succeeded().output

        assertContains(output, "the plugin declined to configure this task: the POM gives maven-surefire-plugin the surefire-junit4 provider")
        assertContains(output, "remove the legacy provider from the plugin's dependencies")
        assertNoGradleRemedy(output)
    }

    @Test
    fun `a skipped JaCoCo agent audits with the Maven remedy`(@TempDir dir: File) {
        val project = committed(dir)

        val output = FunctionalMaven.run(maven, project, "yoriwake:audit", "-pl", "lib", "-Djacoco.skip=true").succeeded().output

        assertContains(output, "the jacoco-maven-plugin `prepare-agent` execution is skipped")
        assertNoGradleRemedy(output)
    }

    @Test
    fun `an argLine without the JaCoCo agent audits as declined with the Maven remedy`(@TempDir dir: File) {
        val project = committed(dir)
        File(project, "lib/pom.xml").edit {
            it.replace(
                "<artifactId>lib</artifactId>",
                "<artifactId>lib</artifactId>\n  <build><plugins><plugin><artifactId>maven-surefire-plugin</artifactId>" +
                    "<configuration><argLine>-Xmx256m</argLine></configuration></plugin></plugins></build>",
            )
        }
        git(project, "commit", "-q", "-am", "an argLine without the agent")
        FunctionalMaven.run(maven, project, "verify", "-pl", "lib").succeeded()

        val output = FunctionalMaven.run(maven, project, "yoriwake:audit", "-pl", "lib").succeeded().output

        assertContains(output, "the JaCoCo agent does not reach the test JVM's argLine")
        assertContains(output, "keep `@{argLine}` in it")
        assertNoGradleRemedy(output)
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
    fun `explain on an empty change set names Maven's base flag`(@TempDir dir: File) {
        val project = committed(dir)
        FunctionalMaven.run(maven, project, "verify", "-pl", "lib").succeeded()

        val output = FunctionalMaven.run(maven, project, "test-compile", "yoriwake:explain", "-pl", "lib", "-Dyoriwake.base=HEAD")
            .succeeded().output

        assertContains(output, "[yoriwake] lib surefire:test (default-test) would run everything: the change set is empty")
        assertContains(output, "pass -Dyoriwake.base=<ref> to select against a specific base")
        assertFalse("-Pyoriwake" in output, output)
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
