package io.github.zeuspizza.yoriwake.maven

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** Failsafe's `integration-test` captures and selects as Surefire's `test` does, into its own map. */
class FailsafeFunctionalTest {

    private val maven = FunctionalMaven.MATRIX[1]

    private fun File.edit(transform: (String) -> String) = writeText(transform(readText()))

    private fun git(project: File, vararg arguments: String): String {
        val process = ProcessBuilder(listOf("git", "-c", "user.name=t", "-c", "user.email=t@t", "-c", "commit.gpgsign=false") + arguments)
            .directory(project).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "git ${arguments.joinToString(" ")} failed:\n$output" }
        return output.trim()
    }

    /**
     * The demo with Failsafe and JaCoCo's integration agent in `app`, and two integration tests: one
     * reaching `Greeter` through `App`, one reaching `Counter`. Committed, with build output ignored.
     */
    private fun withIntegrationTests(dir: File): File = FunctionalMaven.copy(FunctionalMaven.demo, dir).also { project ->
        File(project, ".gitignore").writeText("target/\n")
        File(project, "pom.xml").edit {
            it.replace(
                "<plugin><artifactId>maven-surefire-plugin</artifactId><version>3.5.6</version></plugin>",
                "<plugin><artifactId>maven-surefire-plugin</artifactId><version>3.5.6</version></plugin>\n" +
                    "        <plugin><artifactId>maven-failsafe-plugin</artifactId><version>3.5.6</version></plugin>",
            ).replace(
                "<goals><goal>prepare-agent</goal></goals>\n          </execution>",
                "<goals><goal>prepare-agent</goal></goals>\n          </execution>\n" +
                    "          <execution>\n            <id>prepare-agent-integration</id>\n" +
                    "            <goals><goal>prepare-agent-integration</goal></goals>\n          </execution>",
            )
        }
        File(project, "app/pom.xml").edit {
            it.replace(
                "</dependencies>",
                "</dependencies>\n  <build>\n    <plugins>\n      <plugin>\n" +
                    "        <artifactId>maven-failsafe-plugin</artifactId>\n" +
                    "        <executions><execution><goals><goal>integration-test</goal><goal>verify</goal></goals></execution></executions>\n" +
                    "      </plugin>\n    </plugins>\n  </build>",
            )
        }
        val tests = File(project, "app/src/test/java/dev/demo/app")
        File(tests, "AppIT.java").writeText(
            """
            package dev.demo.app;

            import static org.junit.jupiter.api.Assertions.assertEquals;

            import org.junit.jupiter.api.Test;

            class AppIT {
                @Test
                void welcomes() {
                    assertEquals("Hello, app", new App().welcome());
                }
            }
            """.trimIndent() + "\n"
        )
        File(tests, "CounterIT.java").writeText(
            """
            package dev.demo.app;

            import static org.junit.jupiter.api.Assertions.assertEquals;

            import dev.demo.lib.Counter;
            import org.junit.jupiter.api.Test;

            class CounterIT {
                @Test
                void doubles() {
                    assertEquals(6, new Counter().twice(3));
                }
            }
            """.trimIndent() + "\n"
        )
        git(project, "init", "-q")
        git(project, "add", ".")
        git(project, "commit", "-q", "-m", "demo with integration tests")
    }

    private val name = "app failsafe:integration-test (default)"

    @Test
    fun `integration tests are captured into their own map`(@TempDir dir: File) {
        val project = withIntegrationTests(dir)

        val output = FunctionalMaven.run(maven, project, "verify").succeeded().output

        assertContains(output, "[yoriwake] $name map updated: ")
        assertContains(output, "[yoriwake] app surefire:test (default-test) map updated: ")
        val appMaps = File(project, ".yoriwake/yoriwake").listFiles().orEmpty().filter { it.name.startsWith("dev.demo-app-") }
        assertEquals(2, appMaps.size, appMaps.joinToString())
    }

    @Test
    fun `a change narrows the integration tests on their own`(@TempDir dir: File) {
        val project = withIntegrationTests(dir)
        FunctionalMaven.run(maven, project, "verify").succeeded()
        File(project, "lib/src/main/java/dev/demo/lib/Counter.java").edit { it.replace("n * 2", "n + n") }
        git(project, "commit", "-q", "-am", "an edit")

        val output = FunctionalMaven.run(maven, project, "verify", "-Dyoriwake.select", "-Dyoriwake.base=HEAD~1")
            .succeeded().output

        val integration = output.substringAfter("[yoriwake] $name selecting against ", "")
        assertContains(integration, "in dev.demo.app.CounterIT")
        assertFalse("in dev.demo.app.AppIT" in integration, output)
    }

    @Test
    fun `a failing integration test still decodes the map`(@TempDir dir: File) {
        val project = withIntegrationTests(dir)
        File(project, "app/src/test/java/dev/demo/app/CounterIT.java").edit { it.replace("assertEquals(6,", "assertEquals(7,") }
        git(project, "commit", "-q", "-am", "a failing integration test")

        // Failsafe records the failure in integration-test and fails the build in verify.
        val output = FunctionalMaven.run(maven, project, "integration-test").succeeded().output

        assertContains(output, "Tests run: 2, Failures: 1")
        assertContains(output, "[yoriwake] $name map updated: ")
    }
}
