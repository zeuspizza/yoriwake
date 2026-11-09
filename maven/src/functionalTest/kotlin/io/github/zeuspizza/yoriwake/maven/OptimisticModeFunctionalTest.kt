package io.github.zeuspizza.yoriwake.maven

import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.security.MessageDigest
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `-Dyoriwake.mode=optimistic` on Maven: a selecting execution leaves out the tests only the windows a
 * change opens in a shared JVM select, warns once per execution, and never writes the map.
 *
 * `lib` gains a context built once: B1 builds it from AppConfig, B2 uses what it built. A change to
 * AppConfig reaches B1 by coverage and B2 only through the window.
 */
class OptimisticModeFunctionalTest {

    private val maven = FunctionalMaven.MATRIX[1]

    private fun git(project: File, vararg arguments: String): String {
        val process = ProcessBuilder(listOf("git", "-c", "user.name=t", "-c", "user.email=t@t", "-c", "commit.gpgsign=false") + arguments)
            .directory(project).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "git ${arguments.joinToString(" ")} failed:\n$output" }
        return output.trim()
    }

    private fun source(project: File, path: String, text: String) =
        File(project, "lib/src/$path").apply { parentFile.mkdirs() }.writeText("package dev.demo.lib;\n$text\n")

    private fun test(project: File, name: String, body: String) = source(
        project, "test/java/dev/demo/lib/$name.java",
        "import org.junit.jupiter.api.Test;\nimport static org.junit.jupiter.api.Assertions.*;\nclass $name { $body }",
    )

    /** The demo with the context fixture in `lib`, its tests in name order, committed and captured. */
    private fun captured(dir: File): File = FunctionalMaven.copy(FunctionalMaven.demo, dir).also { project ->
        source(project, "main/java/dev/demo/lib/AppConfig.java", "public class AppConfig { public String word() { return \"hello\"; } }")
        source(
            project, "main/java/dev/demo/lib/AppContext.java",
            """
            public final class AppContext {
                private static AppContext cached;
                private final String word;
                private AppContext(AppConfig config) { this.word = config.word(); }
                public static synchronized AppContext get() {
                    if (cached == null) cached = new AppContext(new AppConfig());
                    return cached;
                }
                public String word() { return word; }
            }
            """.trimIndent(),
        )
        test(project, "B1ContextFirstTest", "@Test void loads() { assertNotNull(AppContext.get()); }")
        test(project, "B2ContextLaterTest", "@Test void reads() { assertFalse(AppContext.get().word().isEmpty()); }")
        File(project, "lib/pom.xml").apply {
            writeText(readText().replace(
                "<artifactId>lib</artifactId>",
                "<artifactId>lib</artifactId>\n  <properties><surefire.runOrder>alphabetical</surefire.runOrder></properties>",
            ))
        }
        File(project, ".gitignore").writeText("target/\n")
        git(project, "init", "-q")
        git(project, "add", ".")
        git(project, "commit", "-q", "-m", "demo")
        FunctionalMaven.run(maven, project, "verify", "-pl", "lib").succeeded()
        File(project, "lib/src/main/java/dev/demo/lib/AppConfig.java").apply { writeText(readText().replace("\"hello\"", "\"hi\"")) }
        git(project, "commit", "-q", "-am", "an edit")
    }

    private fun mapDir(project: File) =
        File(project, ".yoriwake/yoriwake").listFiles().orEmpty().single { it.name.startsWith("dev.demo-lib-") }

    /** Each test's reason column across every test JVM's decision record. */
    private fun reasons(project: File): Map<String, String> =
        mapDir(project).listFiles { file -> file.name.startsWith("decisions.tsv.") && file.name.endsWith(".part") }.orEmpty()
            .flatMap { it.readLines() }
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.split("\t") }
            .associate { it[0] to it[2] }

    /** The map's files a run selects from, by digest, without the records each run writes of itself. */
    private fun mapDigest(project: File): Map<String, String> {
        val root = mapDir(project)
        val perRun = listOf("decisions.tsv", "change-set", "selection.tsv", "task-facts", "explain.json", "run.properties")
        return root.walkTopDown().filter(File::isFile)
            .map { it.relativeTo(root).invariantSeparatorsPath }
            .filterNot { path -> path.startsWith("raw/") || path.endsWith(".pending") || perRun.any { path.startsWith(it) } }
            .associateWith { path ->
                MessageDigest.getInstance("SHA-256").digest(File(root, path).readBytes()).joinToString("") { "%02x".format(it) }
            }
            .toSortedMap()
            .also { assertTrue("coverage.tsv" in it, "the map holds no coverage: $it") }
    }

    private fun warnings(output: String) = output.lines().filter { "optimistic mode:" in it }

    private val select = arrayOf("verify", "-pl", "lib", "-Dyoriwake.select", "-Dyoriwake.base=HEAD~1")

    @Test
    fun `an optimistic execution runs what a kept rule selects, records the rest as loosened and leaves the map`(
        @TempDir dir: File,
    ) {
        val project = captured(dir)
        val before = mapDigest(project)

        val output = FunctionalMaven.run(maven, project, *select, "-Dyoriwake.mode=optimistic").succeeded().output

        val reasons = reasons(project)
        assertEquals("LOOSENED", reasons.entries.single { "B2ContextLaterTest" in it.key }.value, output)
        assertTrue(reasons.entries.single { "B1ContextFirstTest" in it.key }.value != "LOOSENED", output)
        assertEquals(1, warnings(output).size, output)
        // B2, CounterTest and GreeterTest all run after B1 builds the context.
        assertContains(warnings(output).single(), "optimistic mode: 3 tests the safe rule runs were left out")
        assertEquals(before, mapDigest(project))
    }

    @Test
    fun `a mode in maven config fails the build naming the file`(@TempDir dir: File) {
        val project = captured(dir)
        File(project, ".mvn").mkdirs()
        File(project, ".mvn/maven.config").writeText("-Dyoriwake.mode=optimistic\n")

        val output = FunctionalMaven.run(maven, project, *select).output

        assertContains(output, "yoriwake.mode is set in")
        assertContains(output, "maven.config")
    }

    @Test
    fun `an unknown mode fails the build naming the flag`(@TempDir dir: File) {
        val project = captured(dir)

        val result = FunctionalMaven.run(maven, project, *select, "-Dyoriwake.mode=fast")

        assertTrue(result.exitCode != 0, result.output)
        assertContains(result.output, "-Dyoriwake.mode=fast is not a mode")
    }

    @Test
    fun `a map of unknown age runs every test in optimistic mode and captures nothing`(@TempDir dir: File) {
        val project = captured(dir)
        File(mapDir(project), "capture-commit").delete()
        val before = mapDigest(project)

        val output = FunctionalMaven.run(maven, project, *select, "-Dyoriwake.mode=optimistic").succeeded().output

        assertContains(output, "in optimistic mode, which never captures")
        assertContains(output, "Tests run: 4, Failures: 0")
        assertEquals(before, mapDigest(project))
    }

    @Test
    fun `a base git cannot diff against runs every test in optimistic mode and captures nothing`(@TempDir dir: File) {
        assumeFalse(System.getProperty("os.name").startsWith("Windows"), "the failing git is a shell script")
        val project = captured(dir)
        val before = mapDigest(project)
        val real = listOf("/usr/bin/git", "/opt/homebrew/bin/git", "/usr/local/bin/git").first { File(it).canExecute() }
        val bin = File(dir, "bin").apply { mkdirs() }
        File(bin, "git").apply {
            writeText("#!/bin/sh\nif [ \"\$1\" = 'diff' ]; then exit 128; fi\nexec '$real' \"\$@\"\n")
            setExecutable(true)
        }

        val output = FunctionalMaven.run(
            maven, project, *select, "-Dyoriwake.mode=optimistic",
            environment = mapOf("PATH" to bin.path + File.pathSeparator + System.getenv("PATH")),
        ).succeeded().output

        assertContains(output, "git could not report changes")
        assertContains(output, "in optimistic mode, which never captures")
        assertEquals(before, mapDigest(project))
    }

    @Test
    fun `without select the mode has no effect, and the execution records`(@TempDir dir: File) {
        val project = captured(dir)

        val output = FunctionalMaven.run(maven, project, "verify", "-pl", "lib", "-Dyoriwake.mode=optimistic").succeeded().output

        assertContains(output, "-Dyoriwake.mode=optimistic has no effect without -Dyoriwake.select")
        assertTrue(warnings(output).isEmpty(), output)
    }

    @Test
    fun `explain in optimistic mode lists what it leaves out with its rule`(@TempDir dir: File) {
        val project = captured(dir)

        val output = FunctionalMaven.run(
            maven, project, "test-compile", "yoriwake:explain", "-pl", "lib", "-Dyoriwake.base=HEAD~1", "-Dyoriwake.mode=optimistic",
        ).succeeded().output

        assertContains(output, "in optimistic mode leaves out 3 tests the safe rule runs")
        assertContains(output, "B2ContextLaterTest")
        assertContains(output, "(shares-jvm-changed-class)")
    }

    @Test
    fun `on Maven 4 a mode in maven-user properties fails the build naming the file`(@TempDir dir: File) {
        val maven4 = FunctionalMaven.MATRIX.last()
        val project = captured(dir)
        File(project, ".mvn").mkdirs()
        File(project, ".mvn/maven-user.properties").writeText("yoriwake.mode=optimistic\n")

        val result = FunctionalMaven.run(maven4, project, *select)

        assertTrue(result.exitCode != 0, result.output)
        assertContains(result.output, "yoriwake.mode is set in")
        assertContains(result.output, "maven-user.properties")
    }

    @Test
    fun `with the Apache build cache on, an optimistic execution declines as every other does`(@TempDir dir: File) {
        val project = captured(dir)
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
        git(project, "add", ".")
        git(project, "commit", "-q", "-m", "the build cache")

        repeat(2) {
            val output = FunctionalMaven.run(maven, project, *select, "-Dyoriwake.mode=optimistic").succeeded().output
            assertContains(output, "[yoriwake] lib surefire:test (default-test) declined, maven-build-cache: ")
            assertTrue(warnings(output).isEmpty(), output)
        }
    }
}
