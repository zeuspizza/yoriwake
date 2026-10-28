package io.github.zeuspizza.yoriwake.gradle

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `pathIgnore`: declared paths leave the change set, protected paths stay, and every use is named. */
class PathIgnoreFunctionalTest : FunctionalTestSupport() {

    private fun declaring(vararg entries: String, extra: String = "") =
        minimalBuild + "\n$extra\nyoriwake { pathIgnore.addAll(${entries.joinToString { "\"$it\"" }}) }\n"

    private val guide = "docs/guide.md" to "# Guide\n"

    /** The two-class sample and [sources], committed and captured, with the test results cleared. */
    private fun captured(dir: File, buildScript: String, vararg sources: Pair<String, String>) {
        build(dir, "build.gradle.kts" to buildScript, oneClass, oneTest, secondClass, secondTest, classOrderByName, *sources)
        committed(dir)
        runner(dir, "test").build()
        File(dir, "build/test-results").deleteRecursively()
    }

    private fun selecting(dir: File) = runner(dir, "test", "-Pyoriwake.select", "-Pyoriwake.base=HEAD").build().output

    private fun explained(dir: File): String {
        runner(dir, "yoriwakeExplainTest", "-Pyoriwake.base=HEAD").build()
        return File(mapDir(dir), "explain.json").readText()
    }

    private fun mapDir(dir: File) = File(dir, ".gradle/yoriwake").listFiles()!!.single(File::isDirectory)

    /**
     * A test that reads [path] by a name it builds at run time, so no compiled class holds it, and
     * that passes whatever the file says.
     */
    private fun reader(name: String, path: String) = "src/test/java/dev/sample/$name.java" to """
        package dev.sample;
        import java.nio.file.Files;
        import java.nio.file.Paths;
        import org.junit.jupiter.api.Test;
        import static org.junit.jupiter.api.Assertions.assertTrue;
        class $name {
            @Test void reads() throws Exception {
                String path = new StringBuilder("${path.reversed()}").reverse().toString();
                assertTrue(Files.readString(Paths.get(path)).length() > 0);
            }
        }
    """.trimIndent()

    private val laterTest = "src/test/java/dev/sample/ZuluTest.java" to """
        package dev.sample;
        import org.junit.jupiter.api.Test;
        class ZuluTest { @Test void passes() {} }
    """.trimIndent()

    @Test
    fun `a change to declared paths only runs nothing extra and names each path`(@TempDir dir: File) {
        captured(dir, declaring("docs/**"), guide)
        File(dir, "docs/guide.md").writeText("# Guide, again\n")
        File(dir, "docs/new.md").writeText("# New, untracked\n")

        val output = selecting(dir)

        assertEquals(emptySet(), ranTests(dir), output)
        val notes = decisionNotes(dir)
        assertEquals("narrowed", notes["outcome"], output)
        assertEquals("2", notes["paths-ignored"], output)
        assertContains(notes["input.yoriwake.change.ignored"].orEmpty(), "docs/guide.md")
        assertContains(output, "pathIgnore left out 2 changed paths (2 by 'docs/**'): docs/guide.md, docs/new.md")

        val explanation = explained(dir)
        assertContains(explanation, """{ "path": "docs/guide.md", "entry": "docs/**", "evidence": "not-read" }""")
        assertContains(explanation, """"fullRun": false""")
    }

    @Test
    fun `a build that still sets notReadByTests is told it is deprecated`(@TempDir dir: File) {
        build(dir, "build.gradle.kts" to minimalBuild + "\nyoriwake { notReadByTests.add(\"docs/*\") }\n", oneClass, oneTest)

        val output = runner(dir, "help").build().output

        assertContains(output, "notReadByTests is deprecated and will be removed in a later release. Use pathIgnore")
    }

    @Test
    fun `without a declaration a change to documentation forces as before`(@TempDir dir: File) {
        captured(dir, minimalBuild, guide)
        File(dir, "docs/guide.md").writeText("# Guide, again\n")

        val output = selecting(dir)

        assertEquals("unmappable-paths", decisionNotes(dir)["full-run-kind"], output)
        assertEquals(setOf("dev.sample.AlphaTest", "dev.sample.BetaTest"), ranTests(dir), output)
        assertFalse("pathIgnore" in output, output)
    }

    @Test
    fun `a declared path under src stays in the change set, and a forced run still names what it left out`(
        @TempDir dir: File,
    ) {
        captured(dir, declaring("**/*.md"), guide, "src/test/resources/expected.md" to "expected\n")
        File(dir, "docs/guide.md").writeText("# Guide, again\n")
        File(dir, "src/test/resources/expected.md").writeText("expected, again\n")

        val output = selecting(dir)

        val notes = decisionNotes(dir)
        assertEquals("unmappable-paths", notes["full-run-kind"], output)
        assertEquals("1", notes["paths-ignored"], output)
        assertContains(output, "pathIgnore entry '**/*.md' matches src/test/resources/expected.md, which stays in the change set (source-set)")
        assertContains(
            explained(dir),
            """{ "path": "src/test/resources/expected.md", "entry": "**/*.md", "protection": "source-set" }""",
        )
    }

    @Test
    fun `a declared path beside a source change runs what the source change alone runs`(@TempDir dir: File) {
        captured(dir, declaring("docs/**"), guide)
        File(dir, "docs/guide.md").writeText("# Guide, again\n")
        changeBeta(dir)

        val output = selecting(dir)

        assertEquals(setOf("dev.sample.BetaTest"), ranTests(dir), output)
    }

    @Test
    fun `a test that read a declared path at capture runs, with every later test in its JVM`(@TempDir dir: File) {
        captured(
            dir, declaring("docs/**"), "docs/snippets.md" to "snippet\n",
            reader("DocsTest", "docs/snippets.md"), laterTest,
        )
        File(dir, "docs/snippets.md").writeText("snippet, again\n")

        val output = selecting(dir)

        assertEquals(setOf("dev.sample.DocsTest", "dev.sample.ZuluTest"), ranTests(dir), output)
        assertContains(output, "docs/snippets.md was read at capture")
        assertEquals("SHARES_JVM_WITH_CHANGE", decisionReasons(dir).filterKeys { "DocsTest" in it }.values.single(), output)
    }

    @Test
    @DisabledOnOs(OS.WINDOWS, disabledReason = "git for Windows records a link as a plain file unless core.symlinks is on")
    fun `a test that reads through a declared link to a directory runs when the link moves`(@TempDir dir: File) {
        File(dir, "docs").mkdirs()
        val link = File(dir, "docs/current").toPath()
        Files.createSymbolicLink(link, Paths.get("../data/v1"))
        captured(
            dir, declaring("docs/**"), "data/v1/sample.txt" to "one\n", "data/v2/sample.txt" to "two\n",
            reader("DocsTest", "docs/current/sample.txt"), laterTest,
        )
        Files.delete(link)
        Files.createSymbolicLink(link, Paths.get("../data/v2"))

        val output = selecting(dir)

        assertEquals(setOf("dev.sample.DocsTest", "dev.sample.ZuluTest"), ranTests(dir), output)
        assertContains(output, "docs/current was read at capture")
        assertEquals("SHARES_JVM_WITH_CHANGE", decisionReasons(dir).filterKeys { "DocsTest" in it }.values.single(), output)
    }

    @Test
    fun `a test that read a declared source file at capture runs`(@TempDir dir: File) {
        captured(
            dir, declaring("docs/**"), "docs/snippets/Example.kt" to "fun main() {}\n",
            reader("DocsTest", "docs/snippets/Example.kt"), laterTest,
        )
        File(dir, "docs/snippets/Example.kt").writeText("fun main() { println() }\n")

        val output = selecting(dir)

        assertEquals(setOf("dev.sample.DocsTest", "dev.sample.ZuluTest"), ranTests(dir), output)
    }

    @Test
    fun `a declared file the build copies onto the test classpath runs the test that reads the copy`(
        @TempDir dir: File,
    ) {
        captured(
            dir, declaring("**/*.md", extra = "tasks.processResources { from(\"CHANGELOG.md\") }"),
            "CHANGELOG.md" to "# 1.0\n",
            "src/test/java/dev/sample/ChangelogTest.java" to """
                package dev.sample;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertTrue;
                class ChangelogTest {
                    @Test void reads() throws Exception {
                        String name = new StringBuilder("dm.GOLEGNAHC/").reverse().toString();
                        try (java.io.InputStream in = ChangelogTest.class.getResourceAsStream(name)) {
                            assertTrue(in.readAllBytes().length > 0);
                        }
                    }
                }
            """.trimIndent(),
        )
        File(dir, "CHANGELOG.md").writeText("# 1.1\n")

        val output = selecting(dir)

        assertContains(ranTests(dir), "dev.sample.ChangelogTest", output)
        assertContains(output, "pathIgnore left out 1 changed paths")
    }

    @Test
    fun `editing the same declared file twice runs the task both times`(@TempDir dir: File) {
        captured(dir, declaring("docs/**"), guide)

        File(dir, "docs/guide.md").writeText("# Guide, once\n")
        val first = selecting(dir)
        File(dir, "docs/guide.md").writeText("# Guide, twice\n")
        val second = selecting(dir)

        assertContains(first, "pathIgnore left out 1 changed paths")
        assertContains(second, "pathIgnore left out 1 changed paths", message = "the second edit left the task up to date")
    }

    @Test
    fun `a declared path outside a build below the repository root stays in the change set`(@TempDir dir: File) {
        val backend = File(dir, "backend").also { it.mkdirs() }
        build(backend, "build.gradle.kts" to declaring("docs/**"), oneClass, oneTest, secondClass, secondTest)
        File(backend, ".gitignore").writeText("build/\n.gradle/\n")
        File(dir, "docs/x.md").also { it.parentFile.mkdirs() }.writeText("x\n")
        git(dir, "init")
        commit(dir, "base")
        runner(backend, "test").build()
        File(backend, "build/test-results").deleteRecursively()
        File(dir, "docs/x.md").writeText("x, again\n")

        val output = runner(backend, "test", "-Pyoriwake.select", "-Pyoriwake.base=HEAD").build().output

        assertContains(output, "matches docs/x.md, which stays in the change set (outside-root)")
        assertEquals(setOf("dev.sample.AlphaTest", "dev.sample.BetaTest"), ranTests(backend), output)
    }

    @Test
    fun `a declared path a compiled test class names stays in the change set`(@TempDir dir: File) {
        captured(
            dir, declaring("*.md"), "README.md" to "# Readme\n",
            "src/test/java/dev/sample/ReadmeTest.java" to """
                package dev.sample;
                import java.nio.file.Files;
                import java.nio.file.Paths;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertTrue;
                class ReadmeTest {
                    @Test void exists() { assertTrue(Files.exists(Paths.get("README.md"))); }
                }
            """.trimIndent(),
        )
        File(dir, "README.md").writeText("# Readme, again\n")

        val output = selecting(dir)

        assertContains(output, "matches README.md, which stays in the change set (named-in-classes)")
        assertEquals("unmappable-paths", decisionNotes(dir)["full-run-kind"], output)
        assertContains(ranTests(dir), "dev.sample.ReadmeTest", output)
    }

    @Test
    fun `a declared path in a build this one includes stays in the change set`(@TempDir dir: File) {
        // No settings file of its own: what keeps it is that the build includes it.
        captured(
            dir, declaring("**/*.md"),
            "settings.gradle.kts" to "rootProject.name = \"sample\"\nincludeBuild(\"build-logic\")\n",
            "build-logic/build.gradle.kts" to "",
            "build-logic/README.md" to "# Logic\n",
        )
        File(dir, "build-logic/README.md").writeText("# Logic, again\n")

        val output = selecting(dir)

        assertContains(output, "matches build-logic/README.md, which stays in the change set (build-file)")
        assertEquals("unmappable-paths", decisionNotes(dir)["full-run-kind"], output)
        assertEquals(setOf("dev.sample.AlphaTest", "dev.sample.BetaTest"), ranTests(dir), output)
    }

    @Test
    fun `an entry outside the grammar fails the build naming pathIgnore and the entry`(@TempDir dir: File) {
        build(dir, "build.gradle.kts" to declaring("!docs/**"), oneClass, oneTest)

        val output = runner(dir, "test").buildAndFail().output

        assertContains(output, "pathIgnore holds '!docs/**'")
        assertTrue("negation is not supported" in output, output)
    }
}
