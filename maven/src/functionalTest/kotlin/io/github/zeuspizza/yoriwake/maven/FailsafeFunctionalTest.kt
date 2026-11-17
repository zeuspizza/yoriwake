package io.github.zeuspizza.yoriwake.maven

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
        // AppIT first in its JVM: run after CounterIT loaded Counter, a change to Counter rightly selects
        // it, and Failsafe's default order is the file system's, which differs between machines.
        File(project, "app/pom.xml").edit {
            it.replace(
                "</dependencies>",
                "</dependencies>\n  <build>\n    <plugins>\n      <plugin>\n" +
                    "        <artifactId>maven-failsafe-plugin</artifactId>\n" +
                    "        <configuration><runOrder>alphabetical</runOrder></configuration>\n" +
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
    fun `another value in Failsafe's systemPropertiesFile runs every integration test`(@TempDir dir: File) {
        val project = withIntegrationTests(dir)
        // Beside the checkout, so no change to the tree says it changed: only its own properties can.
        val file = File(dir, "local.properties").apply { writeText("mode=fast\n") }
        val named = "-Dfailsafe.systemPropertiesFile=${file.absolutePath}"
        FunctionalMaven.run(maven, project, "verify", named).succeeded()
        file.writeText("mode=slow\n")
        File(project, "lib/src/main/java/dev/demo/lib/Counter.java").edit { it.replace("n * 2", "n + n") }
        git(project, "commit", "-q", "-am", "an edit")

        val output = FunctionalMaven.run(maven, project, "verify", "-Dyoriwake.select", "-Dyoriwake.base=HEAD~1", named)
            .succeeded().output

        // Failsafe alone reads the file: Surefire's executions still narrow.
        val refusal = output.lines().single { "differs from the one the map was recorded in" in it }
        assertContains(refusal, name)
        assertContains(refusal, "sys:mode")
        val map = File(project, ".yoriwake/yoriwake").listFiles().orEmpty().single { it.name.startsWith("dev.demo-app-") && "failsafe" in it.name }
        assertContains(File(map, "decisions.tsv").readLines(), "#!refusal-kind\tenvironment-changed")
        val integration = output.substringAfter(refusal)
        assertContains(integration, "Running dev.demo.app.AppIT")
        assertContains(integration, "Running dev.demo.app.CounterIT")
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

    @Test
    fun `a Failsafe execution records a union`(@TempDir dir: File) {
        val project = withIntegrationTests(dir)

        FunctionalMaven.run(maven, project, "verify", "-Dyoriwake.internal.loaded").succeeded()

        val maps = File(project, ".yoriwake/yoriwake").listFiles().orEmpty().filter { it.name.startsWith("dev.demo-app-") }
        val integration = maps.single { "failsafe" in it.name }
        assertTrue(File(integration, "loaded.txt").isFile, "no union in $integration")
    }

    @Test
    fun `a class nothing loads narrows the integration tests, unless a mojo that may start a process ran first`(@TempDir dir: File) {
        val project = withIntegrationTests(dir)
        val orphan = File(project, "lib/src/main/java/dev/demo/lib/Orphan.java")
        orphan.writeText("package dev.demo.lib;\n\npublic class Orphan {\n    public int once() { return 1; }\n}\n")
        git(project, "add", ".")
        git(project, "commit", "-q", "-m", "a class nothing loads")
        FunctionalMaven.run(maven, project, "verify", "-Dyoriwake.internal.loaded").succeeded()
        orphan.edit { it.replace("return 1", "return 2") }
        git(project, "commit", "-q", "-am", "an edit")
        val select = arrayOf("-Dyoriwake.select", "-Dyoriwake.base=HEAD~1", "-Dyoriwake.internal.loaded")

        val narrowed = FunctionalMaven.run(maven, project, "verify", *select).succeeded().output
        // A goal typed before the build is a mojo too, which may start a server the tests then call.
        val forced = FunctionalMaven.run(maven, project, "help:active-profiles", "verify", *select).succeeded().output

        assertFalse("Running dev.demo.app.AppIT" in narrowed.substringAfter("[yoriwake] $name selecting against ", ""), narrowed)
        assertContains(forced.substringAfter("[yoriwake] $name ", ""), "Running dev.demo.app.AppIT")
    }

    @Test
    fun `a Failsafe execution after a mojo that may start a process records no union`(@TempDir dir: File) {
        val project = withIntegrationTests(dir)
        // Where a build starts the application its integration tests drive, in a JVM no union sees.
        File(project, "app/pom.xml").edit {
            it.replace(
                "      <plugin>\n        <artifactId>maven-failsafe-plugin</artifactId>",
                "      <plugin>\n        <artifactId>maven-antrun-plugin</artifactId><version>3.1.0</version>\n" +
                    "        <executions><execution><phase>pre-integration-test</phase><goals><goal>run</goal></goals>" +
                    "<configuration><target><echo message=\"starting the application\"/></target></configuration>" +
                    "</execution></executions>\n      </plugin>\n      <plugin>\n        <artifactId>maven-failsafe-plugin</artifactId>",
            )
        }
        git(project, "commit", "-q", "-am", "start the application before the integration tests")

        val output = FunctionalMaven.run(maven, project, "verify", "-Dyoriwake.internal.loaded").succeeded().output

        val maps = File(project, ".yoriwake/yoriwake").listFiles().orEmpty().filter { it.name.startsWith("dev.demo-app-") }
        val integration = maps.single { "failsafe" in it.name }
        val unit = maps.single { "surefire" in it.name }
        assertFalse(File(integration, "loaded.txt").exists(), "a union in $integration")
        assertTrue(File(unit, "loaded.txt").isFile, "no union in $unit")
        assertContains(output, "keeps no loaded-class union: app maven-antrun-plugin:run ran before it")
    }
}
