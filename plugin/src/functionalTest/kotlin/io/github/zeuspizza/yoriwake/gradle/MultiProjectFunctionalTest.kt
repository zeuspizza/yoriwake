package io.github.zeuspizza.yoriwake.gradle

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
     * The second half: beta's build script still forces, because another module's script can change
     * this task's dependencies.
     */
    @Test
    fun `a change in a module this task cannot see selects nothing, but its build script still forces`(
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

        // A build script in the same module is not a source file, and it can change what alpha
        // compiles against. It has to keep forcing.
        File(dir, "beta/build.gradle.kts").appendText(System.lineSeparator() + "// touched")

        val forced = runner(dir, ":alpha:test", "-Pyoriwake.select").build().output

        assertContains(forced, "1 paths coverage cannot see")
        assertContains(forced, "[yoriwake] of 1 tests discovered: full-run=1")
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

    /**
     * `:lib` holds a constant beside a method; `:app`'s Gate copies the constant, so its source
     * names `:lib` and its bytecode does not. [limitsTest] adds a test in `:app` that executes
     * Limits, so the map knows the class. Captured, then the constant moves.
     */
    private fun constantAcrossModules(dir: File, limitsTest: Boolean): String {
        File(dir, "settings.gradle.kts").writeText("rootProject.name = \"multi\"\ninclude(\"lib\", \"app\")\n")
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
        fun write(path: String, text: String) =
            File(dir, path).apply { parentFile.mkdirs() }.writeText(text.trimIndent())
        write("lib/build.gradle.kts", common)
        write("app/build.gradle.kts", common + System.lineSeparator() + "dependencies { implementation(project(\":lib\")) }")
        val limits = """
            package dev.lib;
            public class Limits {
                public static final int MAX = 10;
                public int max() { return MAX; }
            }
        """
        write("lib/src/main/java/dev/lib/Limits.java", limits)
        write("app/src/main/java/dev/app/Gate.java", """
            package dev.app;
            public class Gate {
                public String classify(int n) { return n == dev.lib.Limits.MAX ? "max" : "other"; }
            }
        """)
        write("app/src/main/java/dev/app/Other.java", "package dev.app;\npublic class Other { public int one() { return 1; } }")
        fun test(name: String, body: String) = write("app/src/test/java/dev/app/$name.java", """
            package dev.app;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertTrue;
            class $name { @Test void passes() { $body } }
        """)
        // By name: the unrelated test runs before the JVM first loads Gate or Limits.
        test("A0OtherTest", "assertTrue(new Other().one() > 0);")
        test("A1GateTest", "assertTrue(new Gate().classify(3).length() > 0);")
        if (limitsTest) test("A2LimitsTest", "assertTrue(new dev.lib.Limits().max() > 0);")
        write("app/" + classOrderByName.first, classOrderByName.second)
        committed(dir)
        runner(dir, ":app:test").build()
        val gate = File(dir, "app/build/classes/java/main/dev/app/Gate.class")
        val before = gate.readBytes()
        File(dir, "app/build/test-results").deleteRecursively()
        write("lib/src/main/java/dev/lib/Limits.java", limits.replace("MAX = 10", "MAX = 11"))
        commit(dir, "the constant moves")

        val output = runner(dir, ":app:test", "-Pyoriwake.select").build().output

        assertFalse(before.contentEquals(gate.readBytes()), "Gate was not recompiled with the new value")
        assertFalse("declares a compile-time constant" in output, output)
        return output
    }

    @Test
    fun `a changed constant in one module selects the test of the class in another that copied it`(
        @TempDir dir: File,
    ) {
        val output = constantAcrossModules(dir, limitsTest = true)

        assertEquals("narrowed", decisionNotes(dir)["outcome"], output)
        assertEquals(setOf("dev.app.A1GateTest", "dev.app.A2LimitsTest"), ranTests(File(dir, "app")), output)
    }

    @Test
    fun `a changed constant in a class no test recorded never forces on the constant`(
        @TempDir dir: File,
    ) {
        val output = constantAcrossModules(dir, limitsTest = false)

        // Whether a class no test recorded forces is another rule's to say; when it does, the
        // record names that rule and not the constant.
        val notes = decisionNotes(dir)
        if (notes["outcome"] == "full-run") assertEquals("no-coverage-for-changed", notes["full-run-kind"], output)
        assertTrue("dev.app.A1GateTest" in ranTests(File(dir, "app")), output)
    }
}
