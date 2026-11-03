package io.github.zeuspizza.yoriwake.maven

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * A released version in the local repository whose bytes changed since the capture runs every test,
 * whatever wrote them: a copy, an install of the same version, or a redeployed release downloaded
 * afresh. The same version resolved from another repository keeps its bytes: Maven 3.9.16 only checks
 * that the other repository has it.
 */
class ReleasedDependenciesFunctionalTest {

    private val maven = FunctionalMaven.MATRIX[1]

    /** Coordinates of this test's own, so no other test resolves the release it replaces. */
    private val groupId = "dev.fixture${System.nanoTime()}"
    private val inLocalRepository = File(FunctionalMaven.localRepo, groupId.replace('.', '/'))
    private val released get() = File(inLocalRepository, "released/1.0/released-1.0.jar")

    @AfterEach
    fun removeRelease() {
        inLocalRepository.deleteRecursively()
    }

    private fun File.edit(transform: (String) -> String) = writeText(transform(readText()))

    private fun git(project: File, vararg arguments: String): String {
        val process = ProcessBuilder(listOf("git", "-c", "user.name=t", "-c", "user.email=t@t", "-c", "commit.gpgsign=false") + arguments)
            .directory(project).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "git ${arguments.joinToString(" ")} failed:\n$output" }
        return output.trim()
    }

    /** A jar whose one resource holds [flavor], so each flavor has other bytes. */
    private fun jar(target: File, flavor: String): File {
        target.parentFile.mkdirs()
        JarOutputStream(target.outputStream()).use { out ->
            out.putNextEntry(JarEntry("released.properties"))
            out.write("flavor=$flavor\n".toByteArray())
            out.closeEntry()
        }
        return target
    }

    /** Installs release 1.0 of [flavor] into the shared local repository, as `install:install-file` does. */
    private fun install(dir: File, flavor: String) {
        val staging = File(dir, "staging-$flavor").apply { deleteRecursively(); mkdirs() }
        val file = jar(File(staging, "released.jar"), flavor)
        FunctionalMaven.run(
            maven, staging, "org.apache.maven.plugins:maven-install-plugin:3.1.3:install-file",
            "-Dfile=${file.path}", "-DgroupId=$groupId", "-DartifactId=released", "-Dversion=1.0", "-Dpackaging=jar",
        ).succeeded()
    }

    /** Release 1.0 of [flavor] in a `file:` repository at [repository], outside the checkout. */
    private fun deploy(repository: File, flavor: String) {
        val version = File(repository, "${groupId.replace('.', '/')}/released/1.0")
        jar(File(version, "released-1.0.jar"), flavor)
        File(version, "released-1.0.pom").writeText(
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>$groupId</groupId>
              <artifactId>released</artifactId>
              <version>1.0</version>
            </project>
            """.trimIndent(),
        )
    }

    /**
     * The demo with release 1.0 on `lib`'s test classpath, [extra] added to `lib`'s POM, committed and
     * captured with [arguments]; then a Greeter edit committed, so a selecting run against `HEAD~1`
     * narrows to its test when nothing else moved.
     */
    private fun captured(dir: File, extra: String = "", vararg arguments: String): File =
        FunctionalMaven.copy(FunctionalMaven.demo, File(dir, "project")).also { project ->
            File(project, ".gitignore").writeText("target/\n")
            File(project, "lib/pom.xml").edit {
                it.replace(
                    "<artifactId>lib</artifactId>",
                    "<artifactId>lib</artifactId>\n  <dependencies><dependency><groupId>$groupId</groupId><artifactId>released</artifactId>" +
                        "<version>1.0</version><scope>test</scope></dependency></dependencies>$extra",
                )
            }
            git(project, "init", "-q")
            git(project, "add", ".")
            git(project, "commit", "-q", "-m", "demo with a released dependency")
            FunctionalMaven.run(maven, project, "verify", *arguments).succeeded()
            File(project, "lib/src/main/java/dev/demo/lib/Greeter.java").edit { it.replace("\"Hello, \"", "\"Hello,\" + \" \"") }
            git(project, "commit", "-q", "-am", "an edit")
        }

    private fun select(project: File, vararg arguments: String) =
        FunctionalMaven.run(maven, project, "verify", "-pl", "lib", "-Dyoriwake.select", "-Dyoriwake.base=HEAD~1", *arguments)
            .succeeded().output

    private fun assertRanEverything(output: String) {
        assertContains(output, "dependencies on this task's classpath changed since the map was captured")
        assertContains(output, "released-1.0.jar")
        assertContains(output, "Tests run: 2, Failures: 0")
    }

    /** The flavor the local repository's release holds now: proof the sequence reached it. */
    private fun flavor() = java.util.jar.JarFile(released).use { zip ->
        zip.getInputStream(zip.getEntry("released.properties")).bufferedReader().readText().trim().substringAfter("=")
    }

    @Test
    fun `a released dependency copied over in the local repository runs every test`(@TempDir dir: File) {
        install(dir, "one")
        val project = captured(dir)
        jar(File(dir, "copy/released-1.0.jar"), "two").copyTo(released, overwrite = true)

        assertRanEverything(select(project))
    }

    @Test
    fun `a released dependency installed again at the same version runs every test`(@TempDir dir: File) {
        install(dir, "one")
        val project = captured(dir)
        install(dir, "two")

        assertRanEverything(select(project))
    }

    @Test
    fun `a released dependency redeployed and downloaded afresh runs every test`(@TempDir dir: File, @TempDir remote: File) {
        deploy(remote, "one")
        val repository = "<repositories><repository><id>fixture</id><url>${remote.toURI()}</url></repository></repositories>"
        val project = captured(dir, repository)
        deploy(remote, "two")
        File(inLocalRepository, "released").deleteRecursively()

        val output = select(project)

        assertEquals("two", flavor(), output)
        assertRanEverything(output)
    }

    @Test
    fun `an unchanged released dependency selects as before`(@TempDir dir: File) {
        install(dir, "one")
        val project = captured(dir)

        repeat(2) {
            val output = select(project)

            val selecting = output.substringAfter("[yoriwake] lib surefire:test (default-test) selecting against ", "")
            assertContains(selecting, "in dev.demo.lib.GreeterTest")
            assertFalse("in dev.demo.lib.CounterTest" in selecting, output)
            assertFalse("dependencies on this task's classpath changed" in output, output)
        }
    }
}
