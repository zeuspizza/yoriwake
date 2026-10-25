package io.github.zeuspizza.yoriwake.maven

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertContains

/**
 * Each execution Maven cannot select for is named with its token, runs every test, and is left as
 * the host configured it.
 */
class DeclineFunctionalTest {

    private val maven = FunctionalMaven.MATRIX[1]

    private fun File.edit(transform: (String) -> String) = writeText(transform(readText()))

    private fun declined(project: File, vararg arguments: String): String =
        FunctionalMaven.run(maven, project, "verify", *arguments).succeeded().output

    @Test
    fun `forkCount 0 runs Surefire inside Maven's own JVM and is declined`(@TempDir dir: File) {
        val project = FunctionalMaven.copy(FunctionalMaven.demo, dir)
        File(project, "lib/pom.xml").edit {
            it.replace(
                "<artifactId>lib</artifactId>",
                "<artifactId>lib</artifactId>\n  <build><plugins><plugin><artifactId>maven-surefire-plugin</artifactId>" +
                    "<configuration><forkCount>0</forkCount></configuration></plugin></plugins></build>",
            )
        }

        val output = declined(project)

        assertContains(output, "[yoriwake] lib surefire:test (default-test) declined, surefire-in-process: forkCount=0")
        assertContains(output, "Tests run: 2, Failures: 0")
    }

    @Test
    fun `a Surefire older than 3_0_0 is declined`(@TempDir dir: File) {
        val project = FunctionalMaven.copy(FunctionalMaven.demo, dir)
        File(project, "pom.xml").edit {
            it.replace("<artifactId>maven-surefire-plugin</artifactId><version>3.5.6</version>", "<artifactId>maven-surefire-plugin</artifactId><version>2.22.2</version>")
        }

        // Only `lib`: Surefire 2 cannot run the module-path module on this JDK.
        val output = declined(project, "-pl", "lib")

        assertContains(output, "[yoriwake] lib surefire:test (default-test) declined, surefire-version: maven-surefire-plugin 2.22.2 is older than 3.0.0")
    }

    @Test
    fun `a legacy provider named in the POM is declined`(@TempDir dir: File) {
        val project = FunctionalMaven.copy(FunctionalMaven.demo, dir)
        File(project, "lib/pom.xml").edit {
            it.replace(
                "<artifactId>lib</artifactId>",
                "<artifactId>lib</artifactId>\n  <build><plugins><plugin><artifactId>maven-surefire-plugin</artifactId><dependencies>" +
                    "<dependency><groupId>org.apache.maven.surefire</groupId><artifactId>surefire-junit4</artifactId>" +
                    "<version>3.5.6</version></dependency></dependencies></plugin></plugins></build>\n" +
                    "  <dependencies><dependency><groupId>junit</groupId><artifactId>junit</artifactId>" +
                    "<version>4.13.2</version><scope>test</scope></dependency></dependencies>",
            )
        }

        val output = declined(project)

        assertContains(output, "[yoriwake] lib surefire:test (default-test) declined, surefire-legacy-provider")
    }

    @Test
    fun `a build without JaCoCo's agent is declined`(@TempDir dir: File) {
        val project = FunctionalMaven.copy(FunctionalMaven.demo, dir)
        File(project, "pom.xml").edit { it.replace(Regex("""\s*<plugin>\s*<groupId>org.jacoco</groupId>.*?</plugin>""", RegexOption.DOT_MATCHES_ALL), "") }

        val output = declined(project)

        assertContains(output, "[yoriwake] lib surefire:test (default-test) declined, no-jacoco-plugin")
        assertContains(output, "Tests run: 2, Failures: 0")
    }

    @Test
    fun `jacoco skip is declined`(@TempDir dir: File) {
        val project = FunctionalMaven.copy(FunctionalMaven.demo, dir)

        val output = declined(project, "-Djacoco.skip=true")

        assertContains(output, "[yoriwake] lib surefire:test (default-test) declined, jacoco-disabled")
    }
}
