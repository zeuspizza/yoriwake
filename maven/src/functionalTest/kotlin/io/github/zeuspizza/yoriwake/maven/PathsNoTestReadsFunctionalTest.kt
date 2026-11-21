package io.github.zeuspizza.yoriwake.maven

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

/**
 * `<pathIgnore>` and its defaults on Maven: a change to only paths no test reads runs nothing
 * extra, a path a test read at capture still runs that test, and what compiles or builds stays.
 */
class PathsNoTestReadsFunctionalTest {

    private val maven = FunctionalMaven.MATRIX[1]

    private fun git(project: File, vararg arguments: String): String {
        val process = ProcessBuilder(listOf("git", "-c", "user.name=t", "-c", "user.email=t@t", "-c", "commit.gpgsign=false") + arguments)
            .directory(project).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "git ${arguments.joinToString(" ")} failed:\n$output" }
        return output.trim()
    }

    private fun write(project: File, path: String, text: String) =
        File(project, path).apply { parentFile.mkdirs() }.writeText(text)

    /**
     * The demo with documentation and CI configuration beside it, the plugin given [configuration],
     * [files] added, committed, and lib captured.
     */
    private fun captured(dir: File, configuration: String = "", vararg files: Pair<String, String>): File =
        FunctionalMaven.copy(FunctionalMaven.demo, dir).also { project ->
            File(project, ".gitignore").writeText("target/\n")
            write(project, "CHANGES.md", "# Changes\n")
            write(project, "docs/guide.md", "# Guide\n")
            write(project, ".github/workflows/ci.yml", "on: push\n")
            files.forEach { (path, text) -> write(project, path, text) }
            if (configuration.isNotEmpty()) {
                File(project, "pom.xml").apply {
                    writeText(
                        readText().replace(
                            "<extensions>true</extensions>\n      </plugin>",
                            "<extensions>true</extensions>\n        <configuration>$configuration</configuration>\n      </plugin>",
                        )
                    )
                }
            }
            git(project, "init", "-q")
            git(project, "add", ".")
            git(project, "commit", "-q", "-m", "demo")
            FunctionalMaven.run(maven, project, "verify", "-pl", "lib").succeeded()
        }

    private fun selecting(project: File): String =
        FunctionalMaven.run(maven, project, "verify", "-pl", "lib", "-Dyoriwake.select", "-Dyoriwake.base=HEAD").succeeded().output

    private fun mapDir(project: File) =
        File(project, ".yoriwake/yoriwake").listFiles().orEmpty().single { it.name.startsWith("dev.demo-lib-") }

    /** The run notes across every test JVM's decision record. */
    private fun notes(project: File): Map<String, String> =
        mapDir(project).listFiles { file -> file.name.startsWith("decisions.tsv") }.orEmpty()
            .flatMap { it.readLines() }.filter { it.startsWith("#!") }
            .associate { line -> line.removePrefix("#!").split("\t", limit = 2).let { it[0] to it.getOrElse(1) { "" } } }

    private fun ran(output: String): Set<String> =
        Regex("Running (dev\\.demo\\.lib\\.\\w+)").findAll(output).map { it.groupValues[1] }.toSet()

    private val docsTest = "lib/src/test/java/dev/demo/lib/DocsTest.java" to """
        package dev.demo.lib;
        import java.nio.file.Files;
        import java.nio.file.Path;
        import org.junit.jupiter.api.Test;
        class DocsTest {
            @Test void reads() throws Exception {
                try (var files = Files.list(Path.of("../docs"))) {
                    for (var f : (Iterable<Path>) files::iterator) if (f.toString().endsWith(".txt")) Files.readString(f);
                }
            }
        }
    """.trimIndent()

    @Test
    fun `with nothing set a change to documentation and CI configuration only runs nothing extra`(@TempDir dir: File) {
        val project = captured(dir)
        write(project, "README.md", "# Demo, again\n")
        write(project, "CHANGES.md", "# Changes, again\n")
        write(project, "docs/guide.md", "# Guide, again\n")
        write(project, ".github/workflows/ci.yml", "on: pull_request\n")

        val output = selecting(project)

        assertEquals(emptySet(), ran(output), output)
        assertEquals("narrowed", notes(project)["outcome"], output)
        assertEquals("4", notes(project)["paths-ignored"], output)
        assertContains(
            output,
            "pathIgnore left out 4 changed paths (1 by 'default:.github/', 1 by 'default:**/*.md', " +
                "1 by 'default:**/README*', 1 by 'default:docs/'): .github/workflows/ci.yml, CHANGES.md, README.md, docs/guide.md",
        )

        val explained = FunctionalMaven.run(maven, project, "test-compile", "yoriwake:explain", "-pl", "lib", "-Dyoriwake.base=HEAD")
            .succeeded().output
        val explanation = File(mapDir(project), "explain.json").readText()
        assertContains(explanation, """{ "path": "docs/guide.md", "entry": "docs/", "evidence": "not-read", "default": true }""", message = explained)
        assertContains(explanation, """"fullRun": false""", message = explained)
    }

    @Test
    fun `with the defaults turned off a change to documentation forces as before`(@TempDir dir: File) {
        val project = captured(dir, "<pathIgnoreDefaults>false</pathIgnoreDefaults>")
        write(project, "docs/guide.md", "# Guide, again\n")

        val output = selecting(project)

        assertEquals(setOf("dev.demo.lib.GreeterTest", "dev.demo.lib.CounterTest"), ran(output), output)
        assertFalse("pathIgnore left out" in output, output)
    }

    @Test
    fun `a change to declared paths only runs nothing extra and names each path`(@TempDir dir: File) {
        val project = captured(dir, "<pathIgnore><path>notes/**</path></pathIgnore>", "notes/plan.txt" to "plan\n")
        write(project, "notes/plan.txt", "plan, again\n")
        write(project, "notes/new.txt", "untracked\n")

        val output = selecting(project)

        assertEquals(emptySet(), ran(output), output)
        assertContains(output, "pathIgnore left out 2 changed paths (2 by 'notes/**'): notes/new.txt, notes/plan.txt")
    }

    @Test
    fun `a test that read a declared path at capture runs`(@TempDir dir: File) {
        val project = captured(dir, "", "docs/data.txt" to "data\n", docsTest)
        write(project, "docs/data.txt", "data, again\n")

        val output = selecting(project)

        assertContains(ran(output), "dev.demo.lib.DocsTest", output)
        assertContains(output, "docs/data.txt was read at capture")
    }

    @Test
    fun `a declared file under src no module declares as a root runs nothing extra, one in a resource directory stays`(
        @TempDir dir: File,
    ) {
        val project = captured(
            dir, "<pathIgnore><path>**/changes.xml</path></pathIgnore>",
            "src/changes/changes.xml" to "<document/>\n", "lib/src/test/resources/changes.xml" to "<document/>\n",
        )
        write(project, "src/changes/changes.xml", "<document><release/></document>\n")

        val output = selecting(project)

        assertEquals(emptySet(), ran(output), output)
        assertContains(output, "pathIgnore left out 1 changed paths (1 by '**/changes.xml'): src/changes/changes.xml")

        write(project, "lib/src/test/resources/changes.xml", "<document><release/></document>\n")
        val resource = selecting(project)

        assertContains(
            resource,
            "pathIgnore entry '**/changes.xml' matches lib/src/test/resources/changes.xml, which stays in the change set (source-set)",
        )
    }

    @Test
    fun `a test that read a declared file under src at capture runs`(@TempDir dir: File) {
        val changesTest = "lib/src/test/java/dev/demo/lib/ChangesTest.java" to """
            package dev.demo.lib;
            import java.nio.file.Files;
            import java.nio.file.Path;
            import org.junit.jupiter.api.Test;
            class ChangesTest {
                @Test void reads() throws Exception {
                    try (var files = Files.list(Path.of("../src/changes"))) {
                        for (var f : (Iterable<Path>) files::iterator) if (f.toString().endsWith(".xml")) Files.readString(f);
                    }
                }
            }
        """.trimIndent()
        val project = captured(
            dir, "<pathIgnore><path>src/changes/</path></pathIgnore>", "src/changes/changes.xml" to "<document/>\n", changesTest,
        )
        write(project, "src/changes/changes.xml", "<document><release/></document>\n")

        val output = selecting(project)

        assertContains(ran(output), "dev.demo.lib.ChangesTest", output)
        assertContains(output, "src/changes/changes.xml was read at capture")
    }

    @Test
    fun `a resource stamped on every build that stampedFiles declares runs no test that reads it`(@TempDir dir: File) {
        val pom = File(FunctionalMaven.demo, "lib/pom.xml").readText().replace(
            "<artifactId>lib</artifactId>",
            "<artifactId>lib</artifactId>\n" +
                "  <properties><maven.build.timestamp.format>yyyyMMddHHmmssSSS</maven.build.timestamp.format></properties>\n" +
                "  <build><resources><resource><directory>src/main/resources</directory><filtering>true</filtering></resource></resources></build>",
        )
        val stampTest = "lib/src/test/java/dev/demo/lib/StampTest.java" to """
            package dev.demo.lib;
            import java.io.InputStream;
            import java.util.Properties;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertNotNull;
            class StampTest {
                @Test void reads() throws Exception {
                    Properties stamp = new Properties();
                    try (InputStream in = getClass().getResourceAsStream("/stamp.properties")) { stamp.load(in); }
                    assertNotNull(stamp.getProperty("built"));
                }
            }
        """.trimIndent()
        val project = captured(
            dir, "<stampedFiles><path>**/stamp.properties</path></stampedFiles>", "lib/pom.xml" to pom,
            "lib/src/main/resources/stamp.properties" to "built=\${maven.build.timestamp}\n", stampTest,
        )
        File(project, "lib/src/main/java/dev/demo/lib/Greeter.java").apply {
            writeText(readText().replace("    public String greet", "    // Greets by name.\n    public String greet"))
        }

        val output = selecting(project)

        assertEquals(setOf("dev.demo.lib.GreeterTest"), ran(output), output)
        assertContains(output, "stampedFiles left out 1 changed file(s) of build output")
        assertContains(output, "lib/target/classes/stamp.properties")
        assertContains(output, "test(s) that read them when the map was captured do not run for them")
    }

    @Test
    fun `a declared pom stays in the change set as a build file`(@TempDir dir: File) {
        val project = captured(dir, "<pathIgnore><path>**/*.xml</path></pathIgnore>")
        File(project, "lib/pom.xml").apply { writeText(readText().replace("<artifactId>lib</artifactId>", "<artifactId>lib</artifactId>\n  <!-- touched -->")) }

        val output = selecting(project)

        assertContains(output, "pathIgnore entry '**/*.xml' matches lib/pom.xml, which stays in the change set (build-file)")
    }

    @Test
    fun `a file in a test resource directory outside src stays in the change set`(@TempDir dir: File) {
        val pom = File(FunctionalMaven.demo, "lib/pom.xml").readText().replace(
            "<artifactId>lib</artifactId>",
            "<artifactId>lib</artifactId>\n  <build><testResources><testResource><directory>testdata</directory></testResource></testResources></build>",
        )
        val project = captured(dir, "", "lib/pom.xml" to pom, "lib/testdata/notes.md" to "notes\n")
        write(project, "lib/testdata/notes.md", "notes, again\n")

        val output = selecting(project)

        assertContains(output, "pathIgnore entry 'default:**/*.md' matches lib/testdata/notes.md, which stays in the change set (source-set)")
    }

    @Test
    fun `a file a module copies from its own directory onto the classpath stays, and its other files do not`(@TempDir dir: File) {
        val pom = File(FunctionalMaven.demo, "lib/pom.xml").readText().replace(
            "<artifactId>lib</artifactId>",
            "<artifactId>lib</artifactId>\n  <build><resources><resource><directory>\${project.basedir}</directory>" +
                "<targetPath>META-INF</targetPath><includes><include>NOTES.md</include></includes></resource></resources></build>",
        )
        val project = captured(dir, "", "lib/pom.xml" to pom, "lib/NOTES.md" to "notes\n", "lib/README.md" to "# lib\n")
        write(project, "lib/NOTES.md", "notes, again\n")
        write(project, "lib/README.md", "# lib, again\n")

        val output = selecting(project)

        assertContains(output, "pathIgnore entry 'default:**/*.md' matches lib/NOTES.md, which stays in the change set (source-set)")
        assertContains(output, "pathIgnore left out 1 changed paths (1 by 'default:**/README*'): lib/README.md")
    }

    @Test
    fun `paths pathIgnore takes out are not counted towards largeChangeThreshold`(@TempDir dir: File) {
        val project = captured(dir, "<largeChangeThreshold>2</largeChangeThreshold>")
        write(project, "README.md", "# Demo, again\n")
        write(project, "CHANGES.md", "# Changes, again\n")
        write(project, "docs/guide.md", "# Guide, again\n")
        File(project, "lib/src/main/java/dev/demo/lib/Greeter.java").apply {
            writeText(readText().replace("    public String greet", "    // Greets by name.\n    public String greet"))
        }

        val output = selecting(project)

        assertNotEquals("large-change", notes(project)["full-run-kind"], output)
        assertEquals(setOf("dev.demo.lib.GreeterTest"), ran(output), output)
    }

    @Test
    fun `an entry outside the grammar fails the build naming pathIgnore and the entry`(@TempDir dir: File) {
        val project = FunctionalMaven.copy(FunctionalMaven.demo, dir)
        File(project, "pom.xml").apply {
            writeText(
                readText().replace(
                    "<extensions>true</extensions>\n      </plugin>",
                    "<extensions>true</extensions>\n        <configuration><pathIgnore><path>!docs/**</path></pathIgnore></configuration>\n      </plugin>",
                )
            )
        }
        git(project, "init", "-q")
        git(project, "add", ".")
        git(project, "commit", "-q", "-m", "demo")

        val result = FunctionalMaven.run(maven, project, "verify", "-pl", "lib")

        assertNotEquals(0, result.exitCode, result.output)
        assertContains(result.output, "pathIgnore holds '!docs/**', which starts with `!`")
    }
}
