package io.github.zeuspizza.yoriwake.gradle

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** Builds with several projects: scope derivation and cross-module changes. */
class MultiProjectFunctionalTest : FunctionalTestSupport() {

    @Test
    fun `every module derives the same scope, whatever order they are evaluated in`(
        @TempDir dir: File,
    ) {
        // Derived inside a project's own afterEvaluate, the scope would depend on how many siblings
        // had been configured so far. `alpha` is evaluated before `beta`, so both must see both.
        File(dir, "settings.gradle.kts").writeText(
            """
            rootProject.name = "multi"
            include("alpha", "beta")
            """.trimIndent()
        )
        val module = """
            plugins { java; jacoco; id("io.github.zeuspizza.yoriwake") }
            repositories { mavenCentral() }
            dependencies {
                testImplementation(platform("org.junit:junit-bom:5.11.4"))
                testImplementation("org.junit.jupiter:junit-jupiter")
                testRuntimeOnly("org.junit.platform:junit-platform-launcher")
            }
            tasks.test { useJUnitPlatform() }
        """.trimIndent()
        listOf("alpha", "beta").forEach { name ->
            File(dir, "$name/build.gradle.kts").apply { parentFile.mkdirs() }.writeText(module)
            File(dir, "$name/src/main/java/dev/$name/Thing.java").apply { parentFile.mkdirs() }
                .writeText(
                    """
                    package dev.$name;
                    public class Thing { public int n() { return 1; } }
                    """.trimIndent()
                )
            File(dir, "$name/src/test/java/dev/$name/ThingTest.java").apply { parentFile.mkdirs() }
                .writeText(
                    """
                    package dev.$name;
                    import org.junit.jupiter.api.Test;
                    import static org.junit.jupiter.api.Assertions.assertEquals;
                    class ThingTest { @Test void n() { assertEquals(1, new Thing().n()); } }
                    """.trimIndent()
                )
        }

        val output = runner(dir, "test").build().output

        val scopes = output.lines().filter { it.contains("[yoriwake] :") && it.contains("scope=") }
        assertEquals(2, scopes.size, "expected one report per module, got: $scopes")
        scopes.forEach { line ->
            assertContains(line, "dev.alpha")
            assertContains(line, "dev.beta")
        }
    }

    /**
     * Two modules that know nothing about each other, and a change in the one under test's blind spot.
     *
     * `:alpha` does not depend on `:beta`, so nothing beta ships can be in alpha's test JVM.
     *
     * The change is a new Kotlin source in beta. A sibling's class source is dropped only when
     * nothing alpha compiled holds a path to it of two or more segments.
     *
     * The second half: beta's build script drops too. Alpha's test task neither hands it beta's
     * directory nor depends on a task of beta, and neither revision of the script reaches another
     * project or changes its plugins.
     */
    @Test
    fun `a change in a module this task cannot see selects nothing, and so does its build script`(
        @TempDir dir: File,
    ) {
        File(dir, "settings.gradle.kts").writeText(
            """
            rootProject.name = "multi"
            include("alpha", "beta")
            """.trimIndent()
        )
        val module = """
            plugins { java; jacoco; id("io.github.zeuspizza.yoriwake") }
            repositories { mavenCentral() }
            dependencies {
                testImplementation(platform("org.junit:junit-bom:5.11.4"))
                testImplementation("org.junit.jupiter:junit-jupiter")
                testRuntimeOnly("org.junit.platform:junit-platform-launcher")
            }
            tasks.test { useJUnitPlatform() }
        """.trimIndent()
        listOf("alpha", "beta").forEach { name ->
            File(dir, "$name/build.gradle.kts").apply { parentFile.mkdirs() }.writeText(module)
            File(dir, "$name/src/main/java/dev/$name/Thing.java").apply { parentFile.mkdirs() }
                .writeText(
                    """
                    package dev.$name;
                    public class Thing { public int n() { return 1; } }
                    """.trimIndent()
                )
            File(dir, "$name/src/test/java/dev/$name/ThingTest.java").apply { parentFile.mkdirs() }
                .writeText(
                    """
                    package dev.$name;
                    import org.junit.jupiter.api.Test;
                    import static org.junit.jupiter.api.Assertions.assertEquals;
                    class ThingTest { @Test void n() { assertEquals(1, new Thing().n()); } }
                    """.trimIndent()
                )
        }
        ignoreBuildOutputs(dir)
        git(dir, "init")
        git(dir, "-c", "user.email=t@example.com", "-c", "user.name=t", "commit",
            "--allow-empty", "-m", "base")
        git(dir, "add", ".")
        git(dir, "-c", "user.email=t@example.com", "-c", "user.name=t", "commit", "-m", "sample")

        runner(dir, "test").build()

        // A class source whose directories no alpha class names, so nothing alpha runs can name it.
        File(dir, "beta/src/main/kotlin/zq/Flow.kt").apply { parentFile.mkdirs() }
            .writeText("package zq\nclass Flow")
        File(dir, "alpha/build/test-results").deleteRecursively()

        val output = runner(dir, ":alpha:test", "-Pyoriwake.select").build().output

        assertContains(output, "1 in modules that are not on this task's classpath")
        // The tally the filter prints, which names the rule that decided each test. Asserting on it
        // rather than on the absence of result files distinguishes "deselected" from "the task did
        // not run at all", and only the first is the claim being made.
        assertContains(output, "[yoriwake] of 1 tests discovered: skipped=1")

        File(dir, "beta/src/main/kotlin/zq/Flow.kt").delete()
        File(dir, "beta/build.gradle.kts").appendText(System.lineSeparator() + "// touched")
        File(dir, "alpha/build/test-results").deleteRecursively()

        val script = runner(dir, ":alpha:test", "-Pyoriwake.select").build().output

        assertContains(script, "1 in modules that are not on this task's classpath")
        assertContains(script, "are no change: beta/build.gradle.kts")
        assertContains(script, "[yoriwake] of 1 tests discovered: skipped=1")
    }

    /**
     * Alpha and beta, neither depending on the other, each with a class; alpha's test is [alphaTest]
     * and its build script ends with [alphaScript]; [files] are written last and [removed] deleted.
     * Committed, with alpha's map recorded.
     */
    private fun captured(
        dir: File,
        alphaTest: String = THING_TEST,
        alphaScript: String = "",
        files: List<Pair<String, String>> = emptyList(),
        removed: List<String> = emptyList(),
    ) {
        twoJavaModules(dir, alphaTest, alphaScript)
        files.forEach { (path, text) -> File(dir, path).apply { parentFile.mkdirs() }.writeText(text) }
        removed.forEach { File(dir, it).delete() }
        git(dir, "add", "-A")
        git(dir, "-c", "user.email=t@example.com", "-c", "user.name=t", "commit", "--allow-empty", "-m", "files")
        runner(dir, ":alpha:test").build()
        File(dir, "alpha/build/test-results").deleteRecursively()
    }

    private fun selectingAlpha(dir: File) = runner(dir, ":alpha:test", "-Pyoriwake.select").build().output

    @Test
    fun `a sibling's resource this task cannot read selects nothing`(@TempDir dir: File) {
        captured(dir, files = listOf("beta/src/main/resources/x.json" to "{\"n\":1}"))

        File(dir, "beta/src/main/resources/x.json").writeText("{\"n\":2}")
        val output = selectingAlpha(dir)

        assertContains(output, "are no change: beta/src/main/resources/x.json")
        assertContains(output, "[yoriwake] of 1 tests discovered: skipped=1")
    }

    @Test
    fun `a sibling's build script and an own class change select the class's tests only`(@TempDir dir: File) {
        captured(
            dir,
            files = listOf(
                "alpha/src/test/java/dev/alpha/A0OtherTest.java" to """
                    package dev.alpha;
                    import org.junit.jupiter.api.Test;
                    import static org.junit.jupiter.api.Assertions.assertEquals;
                    class A0OtherTest { @Test void two() { assertEquals(2, 1 + 1); } }
                """.trimIndent(),
                "alpha/src/test/resources/junit-platform.properties" to classOrderByName.second,
            ),
        )

        File(dir, "alpha/src/main/java/dev/alpha/Thing.java")
            .writeText("package dev.alpha; public class Thing { public int n() { return 1; } public int m() { return 2; } }")
        File(dir, "beta/build.gradle.kts").appendText(System.lineSeparator() + "// touched")
        val output = selectingAlpha(dir)

        assertContains(output, "are no change: beta/build.gradle.kts")
        assertContains(output, "reaches-change=1")
        assertContains(output, "skipped=1")
    }

    @Test
    fun `a sibling's build file of another name drops, and a build script beside it that Gradle does not read forces`(
        @TempDir dir: File,
    ) {
        captured(
            dir,
            files = listOf(
                "settings.gradle.kts" to "rootProject.name = \"multi\"\ninclude(\"alpha\", \"beta\")\nproject(\":beta\").buildFileName = \"beta.gradle\"\n",
                "beta/beta.gradle" to "plugins { id 'java' }\n",
            ),
            removed = listOf("beta/build.gradle.kts"),
        )

        File(dir, "beta/beta.gradle").appendText("// touched\n")
        val dropped = selectingAlpha(dir)

        assertContains(dropped, "are no change: beta/beta.gradle")
        assertContains(dropped, "[yoriwake] of 1 tests discovered: skipped=1")

        git(dir, "checkout", "--", "beta/beta.gradle")
        File(dir, "beta/build.gradle").writeText("// not read\n")
        File(dir, "alpha/build/test-results").deleteRecursively()
        val forced = selectingAlpha(dir)

        assertContains(forced, "1 paths coverage cannot see")
        assertContains(forced, "[yoriwake] of 1 tests discovered: full-run=1")
    }

    /**
     * The repository root and a directory above it, handed to the test JVM as a system property
     * and an environment value, reach no module: every task hands one, and counting them would
     * keep every sibling's file.
     */
    @Test
    fun `a sibling's resource drops though the task hands its JVM the root and a directory above it`(@TempDir dir: File) {
        captured(
            dir,
            alphaScript = """
                tasks.test {
                    systemProperty("repository", rootDir.absolutePath)
                    environment("ABOVE_THE_CHECKOUT", rootDir.parentFile.absolutePath)
                }
            """.trimIndent(),
            files = listOf("beta/src/main/resources/x.json" to "{\"n\":1}"),
        )

        File(dir, "beta/src/main/resources/x.json").writeText("{\"n\":2}")
        val output = selectingAlpha(dir)

        assertContains(output, "are no change: beta/src/main/resources/x.json")
        assertContains(output, "[yoriwake] of 1 tests discovered: skipped=1")
    }

    @Test
    fun `the explanation counts a sibling's build script as off the classpath`(@TempDir dir: File) {
        captured(dir)

        File(dir, "beta/build.gradle.kts").appendText(System.lineSeparator() + "// touched")
        runner(dir, ":alpha:yoriwakeExplainTest").build()
        val explanation = File(dir, ".gradle/yoriwake").listFiles()!!
            .map { File(it, "explain.json") }.single(File::isFile).readText()

        assertContains(explanation, "\"offClasspath\": 1,")
        assertContains(explanation, "\"unmappablePaths\": 0,")
    }

    /**
     * A parent project's script configures every project under it, so the tested `:a:b` reads what
     * `:a`'s script sets though `:a` is not on its classpath. A module that holds the tested one never
     * drops its build file.
     */
    @Test
    fun `a parent project's build script still forces its children's tests`(@TempDir dir: File) {
        File(dir, "settings.gradle.kts").writeText("rootProject.name = \"multi\"\ninclude(\"a\", \"a:b\")\n")
        File(dir, "a/build.gradle.kts").apply { parentFile.mkdirs() }.writeText(
            "subprojects { tasks.withType<Test>().configureEach { environment(\"FROM_PARENT\", \"1\") } }\n"
        )
        File(dir, "a/b/build.gradle.kts").apply { parentFile.mkdirs() }.writeText(
            """
            plugins { java; jacoco; id("io.github.zeuspizza.yoriwake") }
            repositories { mavenCentral() }
            dependencies {
                testImplementation(platform("org.junit:junit-bom:5.11.4"))
                testImplementation("org.junit.jupiter:junit-jupiter")
                testRuntimeOnly("org.junit.platform:junit-platform-launcher")
            }
            tasks.test { useJUnitPlatform() }
            """.trimIndent()
        )
        File(dir, "a/b/src/main/java/dev/b/Thing.java").apply { parentFile.mkdirs() }
            .writeText("package dev.b; public class Thing { public int n() { return 1; } }")
        File(dir, "a/b/src/test/java/dev/b/ParentTest.java").apply { parentFile.mkdirs() }.writeText(
            """
            package dev.b;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            class ParentTest { @Test void fromParent() { assertEquals("1", System.getenv("FROM_PARENT")); } }
            """.trimIndent()
        )
        ignoreBuildOutputs(dir)
        git(dir, "init")
        git(dir, "-c", "user.email=t@example.com", "-c", "user.name=t", "commit", "--allow-empty", "-m", "base")
        git(dir, "add", ".")
        git(dir, "-c", "user.email=t@example.com", "-c", "user.name=t", "commit", "-m", "sample")
        runner(dir, ":a:b:test").build()

        File(dir, "a/build.gradle.kts").writeText(
            "subprojects { tasks.withType<Test>().configureEach { environment(\"FROM_PARENT\", \"2\") } }\n"
        )
        File(dir, "a/b/build/test-results").deleteRecursively()
        val output = runner(dir, ":a:b:test", "-Pyoriwake.select").buildAndFail().output

        assertContains(output, "1 paths coverage cannot see")
        assertContains(output, "ParentTest > fromParent() FAILED")
    }

    /** A sibling's files outside `src` keep forcing: its properties and a script another build applies. */
    @Test
    fun `a sibling's gradle properties or applied script still forces`(@TempDir dir: File) {
        captured(dir, files = listOf("beta/gradle.properties" to "x=1\n", "beta/scripts/common.gradle" to "// common\n"))

        File(dir, "beta/gradle.properties").writeText("x=2\n")
        File(dir, "beta/scripts/common.gradle").writeText("// common, again\n")
        val output = selectingAlpha(dir)

        assertContains(output, "2 paths coverage cannot see")
        assertContains(output, "[yoriwake] of 1 tests discovered: full-run=1")
    }

    /**
     * A sibling this task cannot load, but can read.
     *
     * `:alpha` does not depend on `:beta`, and its test opens a golden file in beta's resources by
     * relative path. Absent from the classpath is not absent from the filesystem. The change here
     * breaks the test, so a skip is a passing build and a run is a failing one.
     */
    @Test
    fun `a sibling's file a test reads by path still selects that test`(@TempDir dir: File) {
        File(dir, "settings.gradle.kts").writeText(
            """
            rootProject.name = "multi"
            include("alpha", "beta")
            """.trimIndent()
        )
        val module = """
            plugins { java; jacoco; id("io.github.zeuspizza.yoriwake") }
            repositories { mavenCentral() }
            dependencies {
                testImplementation(platform("org.junit:junit-bom:5.11.4"))
                testImplementation("org.junit.jupiter:junit-jupiter")
                testRuntimeOnly("org.junit.platform:junit-platform-launcher")
            }
            tasks.test { useJUnitPlatform() }
        """.trimIndent()
        // A class in each, or no scope can be derived and every run is a full run -- which would
        // pass this test without asking the rule under test anything.
        listOf("alpha", "beta").forEach { name ->
            File(dir, "$name/build.gradle.kts").apply { parentFile.mkdirs() }.writeText(module)
            File(dir, "$name/src/main/java/dev/$name/Thing.java").apply { parentFile.mkdirs() }
                .writeText("package dev.$name; public class Thing { public int n() { return 1; } }")
        }
        File(dir, "beta/src/main/resources/golden.json").apply { parentFile.mkdirs() }
            .writeText("{\"n\":1}")
        File(dir, "alpha/src/test/java/dev/alpha/GoldenTest.java").apply { parentFile.mkdirs() }
            .writeText(
                """
                package dev.alpha;
                import org.junit.jupiter.api.Test;
                import java.nio.file.Files;
                import java.nio.file.Path;
                import static org.junit.jupiter.api.Assertions.assertEquals;
                class GoldenTest {
                    @Test void golden() throws Exception {
                        assertEquals("{\"n\":1}", Files.readString(Path.of("../beta/src/main/resources/golden.json")));
                    }
                }
                """.trimIndent()
            )
        ignoreBuildOutputs(dir)
        git(dir, "init")
        git(dir, "-c", "user.email=t@example.com", "-c", "user.name=t", "commit",
            "--allow-empty", "-m", "base")
        git(dir, "add", ".")
        git(dir, "-c", "user.email=t@example.com", "-c", "user.name=t", "commit", "-m", "sample")

        runner(dir, ":alpha:test").build()

        File(dir, "beta/src/main/resources/golden.json").writeText("{\"n\":2}")
        // Gradle does not know the test reads it either, so the task would be up to date and no
        // selection would be asked. Removing its results is what makes this a question for yoriwake.
        File(dir, "alpha/build/test-results").deleteRecursively()

        val output = runner(dir, ":alpha:test", "-Pyoriwake.select").buildAndFail().output

        assertContains(output, "GoldenTest > golden() FAILED")
    }

    /** Two modules with a `Thing.java` each under one package root, and a test in alpha. */
    private fun twoJavaModules(dir: File, alphaTest: String, alphaScript: String = "") {
        File(dir, "settings.gradle.kts").writeText(
            """
            rootProject.name = "multi"
            include("alpha", "beta")
            """.trimIndent()
        )
        val module = """
            plugins { java; jacoco; id("io.github.zeuspizza.yoriwake") }
            repositories { mavenCentral() }
            dependencies {
                testImplementation(platform("org.junit:junit-bom:5.11.4"))
                testImplementation("org.junit.jupiter:junit-jupiter")
                testRuntimeOnly("org.junit.platform:junit-platform-launcher")
            }
            tasks.test { useJUnitPlatform() }
        """.trimIndent()
        listOf("alpha", "beta").forEach { name ->
            File(dir, "$name/build.gradle.kts").apply { parentFile.mkdirs() }.writeText(module)
            File(dir, "$name/src/main/java/dev/$name/Thing.java").apply { parentFile.mkdirs() }
                .writeText("package dev.$name; public class Thing { public int n() { return 1; } }")
        }
        File(dir, "alpha/src/test/java/dev/alpha/ThingTest.java").apply { parentFile.mkdirs() }.writeText(alphaTest)
        File(dir, "alpha/build.gradle.kts").appendText("\n$alphaScript")
        ignoreBuildOutputs(dir)
        git(dir, "init")
        git(dir, "-c", "user.email=t@example.com", "-c", "user.name=t", "commit",
            "--allow-empty", "-m", "base")
        git(dir, "add", ".")
        git(dir, "-c", "user.email=t@example.com", "-c", "user.name=t", "commit", "-m", "sample")
    }

    /**
     * Alpha's classes hold `java` (`java/lang/Object`), the package root `dev` and the file name
     * `Thing.java`, every single name in beta's source path. None of them is a path to it.
     */
    @Test
    fun `a change to a Java class in a module this task cannot see selects nothing`(@TempDir dir: File) {
        twoJavaModules(
            dir,
            """
            package dev.alpha;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            class ThingTest { @Test void n() { assertEquals(1, new Thing().n()); } }
            """.trimIndent(),
        )
        runner(dir, ":alpha:test").build()

        File(dir, "beta/src/main/java/dev/beta/Thing.java")
            .writeText("package dev.beta; public class Thing { public int n() { return 2; } }")
        File(dir, "alpha/build/test-results").deleteRecursively()

        val output = runner(dir, ":alpha:test", "-Pyoriwake.select").build().output

        assertContains(output, "1 in modules that are not on this task's classpath")
        assertContains(output, "[yoriwake] of 1 tests discovered: skipped=1")
    }

    /**
     * Alpha compiles beta's sources through a source directory of its own, without depending on
     * beta. The change breaks the test, so a skip is a passing build and a run is a failing one.
     */
    @Test
    fun `a sibling's sources a srcDir compiles in keep their edits`(@TempDir dir: File) {
        twoJavaModules(
            dir,
            """
            package dev.alpha;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            class ThingTest { @Test void n() { assertEquals(1, new dev.beta.Thing().n()); } }
            """.trimIndent(),
            """sourceSets.main { java.srcDir("../beta/src/main/java") }""",
        )
        runner(dir, ":alpha:test").build()

        File(dir, "beta/src/main/java/dev/beta/Thing.java")
            .writeText("package dev.beta; public class Thing { public int n() { return 2; } }")
        File(dir, "alpha/build/test-results").deleteRecursively()

        val output = runner(dir, ":alpha:test", "-Pyoriwake.select").buildAndFail().output

        assertFalse("1 in modules that are not on this task's classpath" in output, output)
        assertContains(output, "ThingTest > n() FAILED")
    }

    /**
     * A test that reads a sibling's source by the path relative to that module, as an architecture
     * or golden test might: the path it holds is a trailing part of the source's, and that keeps it.
     * The change breaks the test, so a skip is a passing build and a run is a failing one.
     */
    @Test
    fun `a sibling's Java source a test reads by its module-relative path still selects that test`(@TempDir dir: File) {
        twoJavaModules(
            dir,
            """
            package dev.alpha;
            import org.junit.jupiter.api.Test;
            import java.nio.file.Files;
            import java.nio.file.Path;
            import static org.junit.jupiter.api.Assertions.assertTrue;
            class ThingTest {
                @Test void reads() throws Exception {
                    Path beta = Path.of("..", "beta");
                    assertTrue(Files.readString(beta.resolve("src/main/java/dev/beta/Thing.java")).contains("return 1;"));
                }
            }
            """.trimIndent(),
        )
        runner(dir, ":alpha:test").build()

        File(dir, "beta/src/main/java/dev/beta/Thing.java")
            .writeText("package dev.beta; public class Thing { public int n() { return 2; } }")
        File(dir, "alpha/build/test-results").deleteRecursively()

        val output = runner(dir, ":alpha:test", "-Pyoriwake.select").buildAndFail().output

        assertContains(output, "ThingTest > reads() FAILED")
    }

    /**
     * A sibling whose build directory is `out`, not `build`: a test that loads its compiled
     * classes by that path reads the edit, though alpha does not depend on beta.
     */
    @Test
    fun `a sibling's own build directory a test reads by path keeps its source edits`(@TempDir dir: File) {
        twoJavaModules(
            dir,
            """
            package dev.alpha;
            import org.junit.jupiter.api.Test;
            import java.net.URL;
            import java.net.URLClassLoader;
            import java.nio.file.Path;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            class ThingTest {
                @Test void reads() throws Exception {
                    URL classes = Path.of("..", "beta/out/classes/java/main").toUri().toURL();
                    try (URLClassLoader loader = new URLClassLoader(new URL[] { classes }, null)) {
                        Object thing = loader.loadClass("dev.beta.Thing").getDeclaredConstructor().newInstance();
                        assertEquals(1, thing.getClass().getMethod("n").invoke(thing));
                    }
                }
            }
            """.trimIndent(),
        )
        File(dir, "beta/build.gradle.kts").appendText("\nlayout.buildDirectory = file(\"out\")\n")
        File(dir, ".gitignore").appendText(System.lineSeparator() + "out/")
        git(dir, "-c", "user.email=t@example.com", "-c", "user.name=t", "commit", "-am", "out")
        runner(dir, ":beta:classes", ":alpha:test").build()

        File(dir, "beta/src/main/java/dev/beta/Thing.java")
            .writeText("package dev.beta; public class Thing { public int n() { return 2; } }")
        File(dir, "alpha/build/test-results").deleteRecursively()

        val output = runner(dir, ":beta:classes", ":alpha:test", "-Pyoriwake.select").buildAndFail().output

        assertContains(output, "ThingTest > reads() FAILED")
    }

    /**
     * A sibling whose build directory lies below the root's, outside its own directory: a test
     * that loads its compiled classes from there reads the edit, though alpha does not depend on
     * beta and the path holds neither `beta/build` nor `beta/src`.
     */
    @Test
    fun `a sibling's build directory outside its own directory keeps its source edits`(@TempDir dir: File) {
        twoJavaModules(
            dir,
            """
            package dev.alpha;
            import org.junit.jupiter.api.Test;
            import java.net.URL;
            import java.net.URLClassLoader;
            import java.nio.file.Path;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            class ThingTest {
                @Test void reads() throws Exception {
                    URL classes = Path.of("..", "build/beta/classes/java/main").toUri().toURL();
                    try (URLClassLoader loader = new URLClassLoader(new URL[] { classes }, null)) {
                        Object thing = loader.loadClass("dev.beta.Thing").getDeclaredConstructor().newInstance();
                        assertEquals(1, thing.getClass().getMethod("n").invoke(thing));
                    }
                }
            }
            """.trimIndent(),
        )
        File(dir, "beta/build.gradle.kts")
            .appendText("\nlayout.buildDirectory = rootProject.layout.buildDirectory.dir(\"beta\")\n")
        git(dir, "-c", "user.email=t@example.com", "-c", "user.name=t", "commit", "-am", "build/beta")
        runner(dir, ":beta:classes", ":alpha:test").build()

        File(dir, "beta/src/main/java/dev/beta/Thing.java")
            .writeText("package dev.beta; public class Thing { public int n() { return 2; } }")
        File(dir, "alpha/build/test-results").deleteRecursively()

        val output = runner(dir, ":beta:classes", ":alpha:test", "-Pyoriwake.select").buildAndFail().output

        assertContains(output, "ThingTest > reads() FAILED")
    }

    /**
     * The other half of the classpath rule, and the one that can fail silently.
     *
     * `:alpha` depends on `:beta` here, and alpha's test exercises beta's class. If presence
     * detection were wrong -- a jar served from somewhere other than beta's build directory, a
     * configuration that did not resolve -- beta's source would be dropped from the change set,
     * alpha's suite would be deselected, and a genuinely broken test would report a saving.
     *
     * So this asserts the test runs.
     */
    @Test
    fun `a change in a module this task does depend on still selects its tests`(@TempDir dir: File) {
        File(dir, "settings.gradle.kts").writeText(
            """
            rootProject.name = "multi"
            include("alpha", "beta")
            """.trimIndent()
        )
        val common = """
            plugins { java; jacoco; id("io.github.zeuspizza.yoriwake") }
            repositories { mavenCentral() }
            dependencies {
                testImplementation(platform("org.junit:junit-bom:5.11.4"))
                testImplementation("org.junit.jupiter:junit-jupiter")
                testRuntimeOnly("org.junit.platform:junit-platform-launcher")
            }
            tasks.test { useJUnitPlatform() }
        """.trimIndent()
        File(dir, "beta/build.gradle.kts").apply { parentFile.mkdirs() }.writeText(common)
        File(dir, "alpha/build.gradle.kts").apply { parentFile.mkdirs() }.writeText(
            common + System.lineSeparator() + "dependencies { implementation(project(\":beta\")) }"
        )
        File(dir, "beta/src/main/java/dev/beta/Thing.java").apply { parentFile.mkdirs() }.writeText(
            """
            package dev.beta;
            public class Thing { public int n() { return 1; } }
            """.trimIndent()
        )
        File(dir, "alpha/src/test/java/dev/alpha/UsesBetaTest.java").apply { parentFile.mkdirs() }
            .writeText(
                """
                package dev.alpha;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertEquals;
                class UsesBetaTest { @Test void n() { assertEquals(1, new dev.beta.Thing().n()); } }
                """.trimIndent()
            )
        ignoreBuildOutputs(dir)
        git(dir, "init")
        git(dir, "-c", "user.email=t@example.com", "-c", "user.name=t", "commit",
            "--allow-empty", "-m", "base")
        git(dir, "add", ".")
        git(dir, "-c", "user.email=t@example.com", "-c", "user.name=t", "commit", "-m", "sample")

        runner(dir, ":alpha:test").build()

        File(dir, "beta/src/main/java/dev/beta/Thing.java").writeText(
            """
            package dev.beta;
            public class Thing { public int n() { return 1; } public int m() { return 2; } }
            """.trimIndent()
        )

        val output = runner(dir, ":alpha:test", "-Pyoriwake.select").build().output

        assertContains(output, "1 changed classes")
        assertContains(output, "[yoriwake] of 1 tests discovered: reaches-change=1")
    }

    private companion object {
        val THING_TEST = """
            package dev.alpha;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            class ThingTest { @Test void n() { assertEquals(1, new Thing().n()); } }
        """.trimIndent()
    }
}
