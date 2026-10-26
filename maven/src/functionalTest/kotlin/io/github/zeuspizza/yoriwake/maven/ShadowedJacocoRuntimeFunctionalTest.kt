package io.github.zeuspizza.yoriwake.maven

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertFalse

/**
 * A JaCoCo runtime of another version on the test classpath comes before the `-javaagent`'s jar:
 * the capture still records through the agent that started, and a selecting run narrows on it.
 */
class ShadowedJacocoRuntimeFunctionalTest {

    private val maven = FunctionalMaven.MATRIX[1]

    private fun File.edit(transform: (String) -> String) = writeText(transform(readText()))

    private fun git(project: File, vararg arguments: String): String {
        val process = ProcessBuilder(listOf("git", "-c", "user.name=t", "-c", "user.email=t@t", "-c", "commit.gpgsign=false") + arguments)
            .directory(project).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "git ${arguments.joinToString(" ")} failed:\n$output" }
        return output.trim()
    }

    @Test
    fun `a capture behind another JaCoCo runtime records a map and a selecting run narrows on it`(@TempDir dir: File) {
        val project = FunctionalMaven.copy(FunctionalMaven.demo, dir)
        File(project, ".gitignore").writeText("target/\n")
        File(project, "lib/pom.xml").edit {
            it.replace(
                "<artifactId>lib</artifactId>",
                "<artifactId>lib</artifactId><dependencies><dependency><groupId>org.jacoco</groupId>" +
                    "<artifactId>org.jacoco.agent</artifactId><version>0.8.13</version><classifier>runtime</classifier>" +
                    "<scope>test</scope></dependency></dependencies>",
            )
        }
        git(project, "init", "-q")
        git(project, "add", ".")
        git(project, "commit", "-q", "-m", "demo with a second JaCoCo runtime")

        val captured = FunctionalMaven.run(maven, project, "verify", "-pl", "lib").succeeded().output

        assertContains(captured, "[yoriwake] lib surefire:test (default-test) map updated: ")
        File(project, "lib/src/main/java/dev/demo/lib/Greeter.java").edit { it.replace("\"Hello, \"", "\"Hello,\" + \" \"") }
        git(project, "commit", "-q", "-am", "an edit")

        val output = FunctionalMaven.run(maven, project, "verify", "-pl", "lib", "-Dyoriwake.select", "-Dyoriwake.base=HEAD~1")
            .succeeded().output

        val selecting = output.substringAfter("[yoriwake] lib surefire:test (default-test) selecting against ", "")
        assertContains(selecting, "in dev.demo.lib.GreeterTest")
        assertFalse("in dev.demo.lib.CounterTest" in selecting, output)
    }
}
