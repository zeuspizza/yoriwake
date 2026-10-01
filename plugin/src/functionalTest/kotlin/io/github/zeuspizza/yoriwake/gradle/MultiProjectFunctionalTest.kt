package io.github.zeuspizza.yoriwake.gradle

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertEquals

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
     * The change is beta's Vue component, not its Java source. A sibling path is dropped only when
     * nothing alpha compiled names it, and alpha's bytecode names `java`, `dev` and `Thing.java` --
     * ancestors and the basename of beta's `Thing.java` -- so that change is kept and forces.
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

        File(dir, "beta/src/components/Flow.vue").apply { parentFile.mkdirs() }
            .writeText("<template><div/></template>")
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
}
