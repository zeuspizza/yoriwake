package io.github.zeuspizza.yoriwake.maven

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.FieldSource
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** One POM entry attaches yoriwake to a Maven build, and on its own it changes nothing. */
class MavenAttachFunctionalTest {

    @ParameterizedTest(name = "Maven {0}")
    @FieldSource("io.github.zeuspizza.yoriwake.maven.FunctionalMaven#MATRIX")
    fun `the plugin attaches on every supported Maven`(version: String, @TempDir dir: File) {
        val project = FunctionalMaven.copy(FunctionalMaven.demo, dir)

        val output = FunctionalMaven.run(version, project, "verify").succeeded().output

        assertContains(output, "[yoriwake] attached to Maven $version")
        assertContains(output, "Tests run: 2, Failures: 0")
    }

    @Test
    fun `a Maven older than the floor gets a named line and an untouched build`(@TempDir dir: File) {
        val project = FunctionalMaven.copy(FunctionalMaven.demo, dir)

        val output = FunctionalMaven.run(FunctionalMaven.BELOW_FLOOR, project, "verify").succeeded().output

        assertContains(output, "[yoriwake] maven-version: Maven ${FunctionalMaven.BELOW_FLOOR} is older than 3.9.0")
        assertFalse("[yoriwake] attached" in output, output)
        assertContains(output, "Tests run: 2, Failures: 0")
    }

    @Test
    fun `enabled false in the plugin's configuration says and changes nothing`(@TempDir dir: File) {
        val project = FunctionalMaven.copy(FunctionalMaven.demo, dir)
        val pom = File(project, "pom.xml")
        pom.writeText(
            pom.readText().replace(
                "<extensions>true</extensions>",
                "<extensions>true</extensions>\n        <configuration><enabled>false</enabled></configuration>",
            )
        )

        val output = FunctionalMaven.run(FunctionalMaven.MATRIX.last(), project, "verify").succeeded().output

        assertFalse("[yoriwake]" in output, output)
    }

    @Test
    fun `Surefire's effective configuration is the same with and without the plugin`(@TempDir dir: File) {
        val withPlugin = FunctionalMaven.copy(FunctionalMaven.demo, File(dir, "with"))
        val without = FunctionalMaven.copy(FunctionalMaven.demo, File(dir, "without"))
        val pom = File(without, "pom.xml")
        pom.writeText(pom.readText().replace(Regex("""\s*<!-- The one entry.*?</plugin>""", RegexOption.DOT_MATCHES_ALL), ""))

        fun surefire(project: File): String {
            val out = File(project, "effective.xml")
            FunctionalMaven.run(
                FunctionalMaven.MATRIX.last(), project, "-pl", "lib", "help:effective-pom", "-Doutput=${out.path}",
            ).succeeded()
            return Regex("""<plugin>(?:(?!</plugin>).)*?<artifactId>maven-surefire-plugin</artifactId>.*?</plugin>""", RegexOption.DOT_MATCHES_ALL)
                .findAll(out.readText()).joinToString("\n") { it.value }
        }

        val expected = surefire(without)
        assertFalse(expected.isEmpty(), "the effective POM names no Surefire plugin")
        assertEquals(expected, surefire(withPlugin))
    }
}
