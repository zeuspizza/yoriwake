package io.github.zeuspizza.yoriwake.maven

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.assertContains
import kotlin.test.assertFalse

/**
 * With `-Dyoriwake.narrowDependencies`, a POM change that only bumps a dependency's version runs
 * the tests whose JVM reached the moved jar; without it, and for a property or plugin version, every
 * test runs.
 */
class DependencyBumpFunctionalTest {

    private val maven = FunctionalMaven.MATRIX[1]

    /** Coordinates of this test's own, so no other test resolves the library it bumps. */
    private val groupId = "dev.bump${System.nanoTime()}"

    @AfterEach
    fun removeLibrary() {
        File(FunctionalMaven.localRepo, groupId.replace('.', '/')).deleteRecursively()
    }

    private fun File.edit(transform: (String) -> String) = writeText(transform(readText()))

    private fun git(project: File, vararg arguments: String) {
        val process = ProcessBuilder(listOf("git", "-c", "user.name=t", "-c", "user.email=t@t", "-c", "commit.gpgsign=false") + arguments)
            .directory(project).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "git ${arguments.joinToString(" ")} failed:\n$output" }
    }

    /** `lib-json` [version] in a `file:` repository inside the checkout, its one resource naming the version. */
    private fun library(repository: File, version: String) {
        val dir = File(repository, "${groupId.replace('.', '/')}/lib-json/$version").apply { mkdirs() }
        JarOutputStream(File(dir, "lib-json-$version.jar").outputStream()).use { out ->
            out.putNextEntry(JarEntry("lib/json/data.txt"))
            out.write(version.toByteArray())
            out.closeEntry()
        }
        File(dir, "lib-json-$version.pom").writeText(
            """<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>""" +
                """<groupId>$groupId</groupId><artifactId>lib-json</artifactId><version>$version</version></project>"""
        )
    }

    /** The demo with `lib-json` 1.0 on `lib`'s test classpath and a test that reads it, committed and captured in isolation. */
    private fun captured(dir: File, properties: String = ""): File = FunctionalMaven.copy(FunctionalMaven.demo, File(dir, "project")).also { project ->
        File(project, ".gitignore").writeText("target/\n")
        val repository = File(project, "repo")
        library(repository, "1.0")
        library(repository, "1.1")
        File(project, "lib/pom.xml").edit {
            it.replace(
                "<artifactId>lib</artifactId>",
                """<artifactId>lib</artifactId>$properties
  <repositories>
    <repository>
      <id>fixture</id>
      <url>${repository.toURI()}</url>
    </repository>
  </repositories>
  <dependencies>
    <dependency>
      <groupId>$groupId</groupId>
      <artifactId>lib-json</artifactId>
      <version>1.0</version>
      <scope>test</scope>
    </dependency>
  </dependencies>""",
            )
        }
        File(project, "lib/src/test/java/dev/demo/lib/JsonTest.java").writeText(
            """
            package dev.demo.lib;
            import static org.junit.jupiter.api.Assertions.assertNotNull;
            import org.junit.jupiter.api.Test;
            class JsonTest {
                @Test
                void reads() { assertNotNull(getClass().getResource("/lib/json/data.txt")); }
            }
            """.trimIndent(),
        )
        git(project, "init", "-q")
        git(project, "add", ".")
        git(project, "commit", "-q", "-m", "demo with a library")
        FunctionalMaven.run(maven, project, "verify", "-pl", "lib", "-Dyoriwake.isolatedCapture").succeeded()
    }

    private fun select(project: File, vararg arguments: String) =
        FunctionalMaven.run(maven, project, "verify", "-pl", "lib", "-Dyoriwake.select", "-Dyoriwake.base=HEAD", *arguments)
            .succeeded().output

    @Test
    fun `a dependency version bump runs the test that reached the moved jar, and only with the flag`(@TempDir dir: File) {
        val project = captured(dir)
        File(project, "lib/pom.xml").edit { it.replace("<version>1.0</version>\n      <scope>test</scope>", "<version>1.1</version>\n      <scope>test</scope>") }

        val narrowed = select(project, "-Dyoriwake.narrowDependencies")

        assertContains(narrowed, "change dependency versions only")
        assertContains(narrowed, "in dev.demo.lib.JsonTest")
        assertFalse("in dev.demo.lib.CounterTest" in narrowed, narrowed)

        val withoutFlag = select(project)

        assertContains(withoutFlag, "in dev.demo.lib.CounterTest")
        assertContains(withoutFlag, "lib/pom.xml")
    }

    @Test
    fun `a property bump under a POM that imports a BOM runs every test, naming the import`(@TempDir dir: File) {
        val project = captured(dir, "\n  <properties>\n    <json.version>1.0</json.version>\n  </properties>")
        File(project, "lib/pom.xml").edit { it.replace("<json.version>1.0<", "<json.version>1.1<") }

        val output = select(project, "-Dyoriwake.narrowDependencies")

        assertContains(output, "in dev.demo.lib.CounterTest")
        assertContains(output, "imports a BOM")
    }
}
