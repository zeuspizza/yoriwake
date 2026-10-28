package io.github.zeuspizza.yoriwake.gradle.breaking

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.assertContains

/**
 * Attempts on `pathIgnore` and the declared dependencies. A declared path leaves the change set
 * unless a protection keeps it, or the map shows a test JVM read it; a path a test or a task
 * declares it depends on selects them whatever coverage saw.
 */
class IgnoredPathsAttempts : BreakAttempt() {

    private fun source(path: String, body: String) = path to body.trimIndent()

    private fun ignoring(vararg entries: String, extra: String = "") =
        minimalBuild + "\n$extra\nyoriwake { pathIgnore.addAll(${entries.joinToString { "\"$it\"" }}) }\n"

    /** A Java expression for [path] that no compiled class holds: the name is reversed at run time. */
    private fun builtAtRunTime(path: String): String {
        val literal = path.reversed().replace("\\", "\\\\").replace("\"", "\\\"")
        return "new StringBuilder(\"$literal\").reverse().toString()"
    }

    private val other = source(
        "src/main/java/dev/sample/Other.java",
        "package dev.sample;\npublic class Other { public int one() { return 1; } }",
    )

    /** Runs first and reads nothing: a selection that keeps the target must still leave it out. */
    private val first = source(
        "src/test/java/dev/sample/A0UnrelatedTest.java",
        """
        package dev.sample;
        import org.junit.jupiter.api.Test;
        import static org.junit.jupiter.api.Assertions.assertEquals;
        class A0UnrelatedTest { @Test void runsFirst() { assertEquals(1, new Other().one()); } }
        """,
    )

    /** A1ReaderTest: passes while the file at [path], opened in its own JVM, says exactly [expected]. */
    private fun reader(path: String, expected: String) = source(
        "src/test/java/dev/sample/A1ReaderTest.java",
        """
        package dev.sample;
        import java.nio.file.Files;
        import java.nio.file.Paths;
        import org.junit.jupiter.api.Test;
        import static org.junit.jupiter.api.Assertions.assertEquals;
        class A1ReaderTest {
            @Test void reads() throws Exception {
                assertEquals("$expected", Files.readString(Paths.get(${builtAtRunTime(path)})));
            }
        }
        """,
    )

    /** A1ResourceTest: passes while the test classpath's resource [name] says exactly [expected]. */
    private fun resourceReader(name: String, expected: String) = source(
        "src/test/java/dev/sample/A1ResourceTest.java",
        """
        package dev.sample;
        import java.io.InputStream;
        import java.nio.charset.StandardCharsets;
        import org.junit.jupiter.api.Test;
        import static org.junit.jupiter.api.Assertions.assertEquals;
        class A1ResourceTest {
            @Test void reads() throws Exception {
                try (InputStream in = getClass().getClassLoader().getResourceAsStream(${builtAtRunTime(name)})) {
                    assertEquals("$expected", new String(in.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
        }
        """,
    )

    /**
     * A1SizeTest: passes while the file at [path] is [size] bytes long. It asks only for the size,
     * which opens nothing, so no read of it is recorded: what keeps it selected is a declaration.
     */
    private fun sizeChecker(path: String, size: Int, tag: String? = null) = source(
        "src/test/java/dev/sample/A1SizeTest.java",
        """
        package dev.sample;
        import java.nio.file.Files;
        import java.nio.file.Paths;
        import org.junit.jupiter.api.Tag;
        import org.junit.jupiter.api.Test;
        import static org.junit.jupiter.api.Assertions.assertEquals;
        ${tag?.let { "@Tag(\"$it\")" } ?: ""}
        class A1SizeTest {
            @Test void sized() throws Exception {
                assertEquals(${size}L, Files.size(Paths.get(${builtAtRunTime(path)})));
            }
        }
        """,
    )

    /** Asserts the selecting run kept a path [entry] matches in the change set by [protection]. */
    private fun assertKept(dir: File, entry: String, protection: String) {
        val output = runner(dir, "yoriwakeExplainTest", "-Pyoriwake.base=HEAD").build().output
        val mapDir = File(dir, ".gradle/yoriwake").listFiles()!!.single(File::isDirectory)
        val explain = File(mapDir, "explain.json").readText()
        assertContains(explain, """"entry": "$entry", "protection": "$protection"""", message = output)
    }

    /**
     * A repository whose top is [parent] and whose Gradle root is its `app` directory, holding
     * [outside] beside it, committed; returns the root. Its git directory sits in `app` with the
     * work tree set to [parent], so the attempt's own `git init` and `git add .` find that one.
     */
    private fun belowTheRepositoryTop(parent: File, vararg outside: Pair<String, String>): File {
        outside.forEach { (path, content) -> File(parent, path).apply { parentFile.mkdirs() }.writeText(content) }
        val app = File(parent, "app").apply { mkdirs() }
        git(app, "init")
        git(app, "config", "core.worktree", parent.canonicalPath)
        git(app, "add", ":/")
        git(app, "-c", "user.email=t@example.com", "-c", "user.name=t", "commit", "-m", "outside the build")
        return app
    }

    // Track: fixtures. Row: IP1.
    @Test
    fun `a single star does not reach into a subdirectory, so a file there stays a change`(@TempDir dir: File) {
        val result = attempt(
            dir, "dev.sample.A1ReaderTest", Aim.Forced("unmappable-paths"),
            listOf(other, first, reader("docs/guide/intro.md", "intro"), "docs/guide/intro.md" to "intro", classOrderByName),
            buildScript = ignoring("docs/*"),
        ) { File(it, "docs/guide/intro.md").writeText("outro") }

        assertOutcome(Outcome.HELD_BY_FORCE, result)
    }

    // Track: fixtures. Row: IP2, IP4.
    @Test
    fun `a double star leaves gradle properties in the change set`(@TempDir dir: File) {
        val result = attempt(
            dir, "dev.sample.A1ResourceTest", Aim.Forced("unmappable-paths"),
            listOf(
                other, first, resourceReader("greeting.txt", "hello"),
                "src/test/resources/greeting.txt" to "@greeting@", "gradle.properties" to "greeting=hello\n",
                classOrderByName,
            ),
            buildScript = ignoring(
                "**",
                extra = """
                    val greeting = providers.gradleProperty("greeting").get()
                    tasks.processTestResources { inputs.property("greeting", greeting); filter(mapOf("tokens" to mapOf("greeting" to greeting)), org.apache.tools.ant.filters.ReplaceTokens::class.java) }
                """.trimIndent(),
            ),
        ) { File(it, "gradle.properties").writeText("greeting=bye\n") }

        assertOutcome(Outcome.HELD_BY_FORCE, result)
        assertKept(dir, "**", "build-file")
    }

    // Track: fixtures. Row: IP3.
    @Test
    fun `a Markdown fixture under src test resources stays in the change set`(@TempDir dir: File) {
        val result = attempt(
            dir, "dev.sample.A1ResourceTest", Aim.Forced("unmappable-paths"),
            listOf(
                other, first, resourceReader("expected.md", "expected"),
                "src/test/resources/expected.md" to "expected", classOrderByName,
            ),
            buildScript = ignoring("**/*.md"),
        ) { File(it, "src/test/resources/expected.md").writeText("unexpected") }

        assertOutcome(Outcome.HELD_BY_FORCE, result)
        assertKept(dir, "**/*.md", "source-set")
    }

    // Track: fixtures. Row: IP5.
    @Test
    fun `a declared path outside a build below the repository top stays in the change set`(@TempDir parent: File) {
        val app = belowTheRepositoryTop(parent, "shared/data.txt" to "one")
        val result = attempt(
            app, "dev.sample.A1ReaderTest", Aim.Forced("unmappable-paths"),
            listOf(other, first, reader("../shared/data.txt", "one"), classOrderByName),
            buildScript = ignoring("shared/**"),
        ) { File(it.parentFile, "shared/data.txt").writeText("two") }

        assertOutcome(Outcome.HELD_BY_FORCE, result)
        assertKept(app, "shared/**", "outside-root")
    }

    // Track: fixtures. Row: IP6.
    @Test
    fun `a readme test naming README md keeps the readme in the change set`(@TempDir dir: File) {
        val readmeTest = source(
            "src/test/java/dev/sample/A1ReadmeTest.java",
            """
            package dev.sample;
            import java.nio.file.Files;
            import java.nio.file.Paths;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertTrue;
            class A1ReadmeTest {
                @Test void documentsUsage() throws Exception {
                    assertTrue(Files.readString(Paths.get("README.md")).contains("## Usage"));
                }
            }
            """,
        )
        val result = attempt(
            dir, "dev.sample.A1ReadmeTest", Aim.Forced("unmappable-paths"),
            listOf(other, first, readmeTest, "README.md" to "# Sample\n## Usage\n", classOrderByName),
            buildScript = ignoring("*.md"),
        ) { File(it, "README.md").writeText("# Sample\n") }

        assertOutcome(Outcome.HELD_BY_FORCE, result)
        assertKept(dir, "*.md", "named-in-classes")
    }

    // Track: fixtures. Row: IP7.
    @Test
    fun `a path git prints in quotes stays in the change set`(@TempDir dir: File) {
        val name = "docs/say\"hi.txt"
        val result = attempt(
            dir, "dev.sample.A1ReaderTest", Aim.Forced("unmappable-paths"),
            listOf(other, first, reader(name, "hi"), name to "hi", classOrderByName),
            buildScript = ignoring("**"),
        ) { File(it, name).writeText("bye") }

        assertOutcome(Outcome.HELD_BY_FORCE, result)
        assertKept(dir, "**", "git-quoted")
    }

    // Track: fixtures. Row: IP8.
    @Test
    fun `a test that read a declared path at capture runs`(@TempDir dir: File) {
        val result = attempt(
            dir, "dev.sample.A1ReaderTest", Aim.Row("shares-jvm-ignored-path"),
            listOf(other, first, reader("docs/snippets.md", "snippet"), "docs/snippets.md" to "snippet", classOrderByName),
            buildScript = ignoring("docs/**"),
        ) { File(it, "docs/snippets.md").writeText("snippet, again") }

        assertOutcome(Outcome.HELD_BY_RULE, result)
    }

    // Track: fixtures. Row: IP9.
    @Test
    fun `a test that hands a declared path to a child process runs`(@TempDir dir: File) {
        val child = source(
            "src/test/java/dev/sample/A1ChildTest.java",
            """
            package dev.sample;
            import java.nio.charset.StandardCharsets;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            class A1ChildTest {
                @Test void catsTheVersion() throws Exception {
                    Process cat = new ProcessBuilder("/bin/cat", ${builtAtRunTime("docs/version.txt")}).start();
                    assertEquals("1", new String(cat.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
                    assertEquals(0, cat.waitFor());
                }
            }
            """,
        )
        val result = attempt(
            dir, "dev.sample.A1ChildTest", Aim.Row("shares-jvm-ignored-path"),
            listOf(other, first, child, "docs/version.txt" to "1", classOrderByName),
            buildScript = ignoring("docs/**"),
        ) { File(it, "docs/version.txt").writeText("2") }

        assertOutcome(Outcome.HELD_BY_RULE, result)
    }

    // Track: fixtures. Row: IP10.
    @Test
    fun `a test that compiles a declared source file at capture runs`(@TempDir dir: File) {
        val compiler = source(
            "src/test/java/dev/sample/A1SnippetTest.java",
            """
            package dev.sample;
            import java.nio.file.Files;
            import javax.tools.ToolProvider;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            class A1SnippetTest {
                @Test void compiles() throws Exception {
                    String out = Files.createTempDirectory("snippet").toString();
                    assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                        "-d", out, ${builtAtRunTime("docs/Snippet.java")}));
                }
            }
            """,
        )
        val result = attempt(
            dir, "dev.sample.A1SnippetTest", Aim.Row("shares-jvm-ignored-path"),
            listOf(other, first, compiler, "docs/Snippet.java" to "class Snippet { int one() { return 1; } }", classOrderByName),
            buildScript = ignoring("docs/**"),
        ) { File(it, "docs/Snippet.java").writeText("class Snippet { int one() { return 1 } }") }

        assertOutcome(Outcome.HELD_BY_RULE, result)
    }

    // Track: fixtures. Row: IP13.
    @Test
    fun `a declared file the build copies onto the test classpath runs the test that reads the copy`(@TempDir dir: File) {
        val result = attempt(
            dir, "dev.sample.A1ResourceTest", Aim.Row("shares-jvm-moved-classpath-file"),
            listOf(other, first, resourceReader("NOTES.md", "v1"), "NOTES.md" to "v1", classOrderByName),
            buildScript = ignoring("*.md", extra = """tasks.processTestResources { from("NOTES.md") }"""),
        ) { File(it, "NOTES.md").writeText("v2") }

        assertOutcome(Outcome.HELD_BY_RULE, result)
    }

    // Track: fixtures. Row: IP14.
    @Test
    fun `a test that declares a dependency on a declared path runs when it changes`(@TempDir dir: File) {
        val result = attempt(
            dir, "dev.sample.A1SizeTest", Aim.Row("declares-changed-path"),
            listOf(
                other, first, sizeChecker("docker/fixture.json", 11, tag = "yoriwake-depends-on:/docker/**"),
                "docker/fixture.json" to "{\"rows\": 1}", classOrderByName,
            ),
            buildScript = ignoring("docker/**"),
        ) { File(it, "docker/fixture.json").writeText("{\"rows\": 12}") }

        assertOutcome(Outcome.HELD_BY_RULE, result)
    }

    // Track: fixtures. Row: IP15.
    @Test
    fun `a path the build declares for the test task runs it in full`(@TempDir dir: File) {
        val result = attempt(
            dir, "dev.sample.A1SizeTest", Aim.Forced("declared-path-changed"),
            listOf(other, first, sizeChecker("docker/fixture.json", 11), "docker/fixture.json" to "{\"rows\": 1}", classOrderByName),
            buildScript = ignoring("docker/**", extra = """yoriwake { dependsOn("test", "/docker/**") }"""),
        ) { File(it, "docker/fixture.json").writeText("{\"rows\": 12}") }

        assertOutcome(Outcome.HELD_BY_FORCE, result)
    }

    // Track: fixtures. Row: IP16.
    @Test
    fun `a declared double star matches no directory at all`(@TempDir dir: File) {
        val result = attempt(
            dir, "dev.sample.A1SizeTest", Aim.Row("declares-changed-path"),
            listOf(
                other, first, sizeChecker("docker/fixture.json", 11, tag = "yoriwake-depends-on:/docker/**/*.json"),
                "docker/fixture.json" to "{\"rows\": 1}", classOrderByName,
            ),
            buildScript = ignoring("docker/**"),
        ) { File(it, "docker/fixture.json").writeText("{\"rows\": 12}") }

        assertOutcome(Outcome.HELD_BY_RULE, result)
    }

    // Track: code reading. Boundary: build-file keeps build scripts, gradle.properties, version
    // catalogs, anything under a gradle directory and build logic, by name. A properties file the
    // build script loads its dependency versions from (refreshVersions' versions.properties) is none
    // of those, and nothing else compares a dependency jar: a version bump in it under `**`.
    @Test
    fun `a dependency version bumped in a properties file the build loads runs the tests on that dependency`(
        @TempDir dir: File,
    ) {
        val versionTest = source(
            "src/test/java/dev/sample/A1VersionTest.java",
            """
            package dev.sample;
            import org.apache.commons.lang3.StringUtils;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            class A1VersionTest {
                @Test void pinned() { assertEquals("3.17.0", StringUtils.class.getPackage().getImplementationVersion()); }
            }
            """,
        )
        val result = attempt(
            dir, "dev.sample.A1VersionTest", Aim.Forced("unmappable-paths"),
            listOf(other, first, versionTest, "versions.properties" to "commons-lang3=3.17.0\n", classOrderByName),
            buildScript = ignoring(
                "**",
                extra = """
                    val lang3 = file("versions.properties").readLines().first { it.startsWith("commons-lang3=") }.substringAfter("=")
                    dependencies { testImplementation("org.apache.commons:commons-lang3:${'$'}lang3") }
                """.trimIndent(),
            ),
        ) { File(it, "versions.properties").writeText("commons-lang3=3.16.0\n") }

        assertOutcome(Outcome.HELD_BY_FORCE, result)
        assertKept(dir, "**", "build-file")
    }

    // Track: code reading. Boundary: source-set keeps a path under any `src` directory or under a
    // directory Gradle reports for a source set. A resource directory the build adds under another
    // name is only kept if the reported directories really include resource directories.
    @Test
    fun `a resource directory not named src keeps its files in the change set`(@TempDir dir: File) {
        val result = attempt(
            dir, "dev.sample.A1ResourceTest", Aim.Forced("unmappable-paths"),
            listOf(other, first, resourceReader("expected.txt", "expected"), "fixtures/expected.txt" to "expected", classOrderByName),
            buildScript = ignoring("fixtures/**", extra = """sourceSets.test { resources.srcDir("fixtures") }"""),
        ) { File(it, "fixtures/expected.txt").writeText("unexpected") }

        assertOutcome(Outcome.HELD_BY_FORCE, result)
        assertKept(dir, "fixtures/**", "source-set")
    }

    // Track: code reading. Boundary: outside-root is judged on git's verbatim string, while paths
    // inside the root are rebased onto it. A path outside that spells the same as one inside
    // (`docs/data.txt` beside `app/docs/data.txt`) must still be taken for the outside one.
    @Test
    fun `a path outside the root that spells like one inside stays in the change set`(@TempDir parent: File) {
        val app = belowTheRepositoryTop(parent, "docs/data.txt" to "one")
        val result = attempt(
            app, "dev.sample.A1ReaderTest", Aim.Forced("unmappable-paths"),
            listOf(other, first, reader("../docs/data.txt", "one"), "docs/data.txt" to "inside", classOrderByName),
            buildScript = ignoring("docs/**"),
        ) { File(it.parentFile, "docs/data.txt").writeText("two") }

        assertOutcome(Outcome.HELD_BY_FORCE, result)
        assertKept(app, "docs/**", "outside-root")
    }

    // Track: code reading. Boundary: git-quoted is the first check after outside-root, on a leading
    // quote, since git escapes a non-ASCII name byte by byte and no entry can match it as written.
    // Only an entry that matches anything (`**`) meets the quoted string; named-in-classes, which
    // counts a non-ASCII name as named, never gets to judge it.
    @Test
    fun `a non-ASCII name git escapes stays in the change set under a double star`(@TempDir dir: File) {
        val name = "docs/café.txt"
        val result = attempt(
            dir, "dev.sample.A1ReaderTest", Aim.Forced("unmappable-paths"),
            listOf(other, first, reader(name, "espresso"), name to "espresso", classOrderByName),
            buildScript = ignoring("**"),
        ) { File(it, name).writeText("ristretto") }

        assertOutcome(Outcome.HELD_BY_FORCE, result)
        assertKept(dir, "**", "git-quoted")
    }

    // Track: code reading. Boundary: named-in-classes searches the task's compiled classes for the
    // path or its file name, not only the test classes. A main class returning the file name from a
    // method (no constant a test class could inline), and a test that only asks for its size, which
    // opens nothing, leave the name as the one thing that ties the test to the file.
    @Test
    fun `a file name a main class holds keeps the file in the change set`(@TempDir dir: File) {
        val names = source(
            "src/main/java/dev/sample/Release.java",
            "package dev.sample;\npublic class Release { public static String changes() { return \"CHANGES.txt\"; } }",
        )
        val sizeTest = source(
            "src/test/java/dev/sample/A1ChangesTest.java",
            """
            package dev.sample;
            import java.nio.file.Files;
            import java.nio.file.Paths;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            class A1ChangesTest { @Test void sized() throws Exception { assertEquals(3L, Files.size(Paths.get(Release.changes()))); } }
            """,
        )
        val result = attempt(
            dir, "dev.sample.A1ChangesTest", Aim.Forced("unmappable-paths"),
            listOf(other, names, first, sizeTest, "CHANGES.txt" to "1.0", classOrderByName),
            buildScript = ignoring("*.txt"),
        ) { File(it, "CHANGES.txt").writeText("1.0.1") }

        assertOutcome(Outcome.HELD_BY_FORCE, result)
        assertKept(dir, "*.txt", "named-in-classes")
    }

    // Track: code reading. Boundary: Selector.readsIgnored counts a read of the declared path, of a
    // directory above it, or of an entry of it as an archive. A declared path git tracks is a file,
    // except a symbolic link: a link to a directory is read through (`docs/current/x.txt`), and the
    // read is recorded under the link's path and the resolved one, neither the link itself.
    @Test
    fun `a test that reads through a declared link to a directory runs when the link moves`(@TempDir dir: File) {
        File(dir, "docs").mkdirs()
        Files.createSymbolicLink(File(dir, "docs/qzlink").toPath(), Paths.get("../data/v1"))
        val result = attempt(
            dir, "dev.sample.A1ReaderTest", Aim.Row("shares-jvm-ignored-path"),
            listOf(
                other, first, reader("docs/qzlink/sample.txt", "one"),
                "data/v1/sample.txt" to "one", "data/v2/sample.txt" to "two", classOrderByName,
            ),
            buildScript = ignoring("docs/**"),
        ) {
            val link = File(it, "docs/qzlink").toPath()
            Files.delete(link)
            Files.createSymbolicLink(link, Paths.get("../data/v2"))
        }

        assertOutcome(Outcome.HELD_BY_RULE, result)
    }

    // Track: code reading. Boundary: a moved classpath file is handed to the selector when it is an
    // entry of one of the build's own jars, not only a file of a resource directory. A declared file
    // the build packs into a jar on the test runtime classpath is read through the JDK's zip code.
    @Test
    fun `a declared file the build packs into a jar on the test classpath runs the test that reads it`(
        @TempDir dir: File,
    ) {
        val result = attempt(
            dir, "dev.sample.A1ResourceTest", Aim.Row("shares-jvm-moved-classpath-file"),
            listOf(other, first, resourceReader("NOTES.md", "v1"), "NOTES.md" to "v1", classOrderByName),
            buildScript = ignoring(
                "*.md",
                extra = """
                    val fixturesJar = tasks.register<Jar>("fixturesJar") {
                        from("NOTES.md")
                        archiveFileName.set("fixtures.jar")
                        destinationDirectory.set(layout.buildDirectory.dir("fixtures"))
                    }
                    dependencies { testRuntimeOnly(files(fixturesJar)) }
                """.trimIndent(),
            ),
        ) { File(it, "NOTES.md").writeText("v2") }

        assertOutcome(Outcome.HELD_BY_RULE, result)
    }

    // Track: code reading. Boundary: the task's dependsOn is matched against every changed path,
    // the working tree's drift included. A new untracked file the glob matches is a changed path
    // like an edited one, for a test that counts what the directory holds.
    @Test
    fun `a new untracked file under a path the build declares runs the task in full`(@TempDir dir: File) {
        val counter = source(
            "src/test/java/dev/sample/A1CountTest.java",
            """
            package dev.sample;
            import java.nio.file.Files;
            import java.nio.file.Paths;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            class A1CountTest {
                @Test void one() throws Exception {
                    try (var listed = Files.list(Paths.get(${builtAtRunTime("docker")}))) { assertEquals(1L, listed.count()); }
                }
            }
            """,
        )
        val result = attempt(
            dir, "dev.sample.A1CountTest", Aim.Forced("declared-path-changed"),
            listOf(other, first, counter, "docker/fixture.json" to "{}", classOrderByName),
            buildScript = ignoring("docker/**", extra = """yoriwake { dependsOn("test", "/docker/**") }"""),
        ) { File(it, "docker/extra.json").writeText("{}") }

        assertOutcome(Outcome.HELD_BY_FORCE, result)
    }

    // Track: code reading. Boundary: SelectionFilter.declaresChangedPath matches a test's declared
    // glob against the change set's changed paths, which reach the test JVM as one comma-joined
    // property and are split on commas there. A changed path whose name holds a comma
    // (`docker/users,v2.json`) arrives as two strings, neither of which the glob matches.
    @Test
    fun `a test that declares a dependency runs when a file with a comma in its name changes`(@TempDir dir: File) {
        val result = attempt(
            dir, "dev.sample.A1SizeTest", Aim.Row("declares-changed-path"),
            listOf(
                other, first, sizeChecker("docker/users,v2.json", 11, tag = "yoriwake-depends-on:/docker/*.json"),
                "docker/users,v2.json" to "{\"rows\": 1}", classOrderByName,
            ),
            buildScript = ignoring("docker/**"),
        ) { File(it, "docker/users,v2.json").writeText("{\"rows\": 12}") }

        assertOutcome(Outcome.HELD_BY_RULE, result)
    }
}
