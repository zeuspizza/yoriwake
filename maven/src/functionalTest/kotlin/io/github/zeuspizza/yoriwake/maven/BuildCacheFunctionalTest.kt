package io.github.zeuspizza.yoriwake.maven

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertFalse

/** No Maven build cache stands in for a wired execution: with the Apache extension on, it declines. */
class BuildCacheFunctionalTest {

    private val maven = FunctionalMaven.MATRIX[1]

    private fun git(project: File, vararg arguments: String): String {
        val process = ProcessBuilder(listOf("git", "-c", "user.name=t", "-c", "user.email=t@t", "-c", "commit.gpgsign=false") + arguments)
            .directory(project).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "git ${arguments.joinToString(" ")} failed:\n$output" }
        return output.trim()
    }

    /** The demo with the Apache build cache extension declared in `.mvn/extensions.xml`, committed. */
    private fun withBuildCache(dir: File): File = FunctionalMaven.copy(FunctionalMaven.demo, dir).also { project ->
        File(project, ".gitignore").writeText("target/\n")
        File(project, ".mvn").mkdirs()
        File(project, ".mvn/extensions.xml").writeText(
            """
            <extensions>
              <extension>
                <groupId>org.apache.maven.extensions</groupId>
                <artifactId>maven-build-cache-extension</artifactId>
                <version>1.3.0</version>
              </extension>
            </extensions>
            """.trimIndent() + "\n"
        )
        git(project, "init", "-q")
        git(project, "add", ".")
        git(project, "commit", "-q", "-m", "demo with the build cache")
    }

    @Test
    fun `with the Apache build cache on every execution declines`(@TempDir dir: File) {
        val project = withBuildCache(dir)

        val output = FunctionalMaven.run(maven, project, "verify").succeeded().output

        assertContains(output, "[yoriwake] lib surefire:test (default-test) declined, maven-build-cache: ")
        assertFalse("map updated" in output, output)
    }

    @Test
    fun `with the cache turned off for the run the execution captures`(@TempDir dir: File) {
        val project = withBuildCache(dir)

        val output = FunctionalMaven.run(maven, project, "verify", "-Dmaven.build.cache.enabled=false").succeeded().output

        assertFalse("declined, maven-build-cache" in output, output)
        assertContains(output, "[yoriwake] lib surefire:test (default-test) map updated: ")
    }
}
