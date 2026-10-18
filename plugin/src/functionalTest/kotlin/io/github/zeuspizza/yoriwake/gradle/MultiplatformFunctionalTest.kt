package io.github.zeuspizza.yoriwake.gradle

import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A Kotlin Multiplatform module with a JVM target: what capture records and what selection decides
 * for each shape of change its source sets allow.
 *
 * The plugin reads a multiplatform module by its directory layout, not through the Kotlin Gradle
 * plugin's model, so these are the shapes that layout reading has to get right. Every change either
 * narrows to the tests that ran it or runs everything; none may leave out a test that reaches it.
 */
class MultiplatformFunctionalTest : FunctionalTestSupport() {

    /** Captures, applies [edit] on top of the commit, and selects against HEAD. */
    private fun selectAfter(
        dir: File,
        edit: () -> Unit,
        run: GradleRunner.() -> BuildResult = GradleRunner::build,
    ): String {
        runner(dir, "jvmTest").build()
        edit()
        File(dir, "build/test-results").deleteRecursively()
        return runner(dir, "jvmTest", "-Pyoriwake.select", "-Pyoriwake.base=HEAD").run().output
    }

    private fun edit(dir: File, path: String, from: String, to: String) {
        val file = File(dir, path)
        val text = file.readText()
        check(from in text) { "$path does not contain $from" }
        file.writeText(text.replace(from, to))
    }

    private fun ran(dir: File, task: String = "jvmTest"): Set<String> =
        File(dir, "build/test-results/$task")
            .listFiles { f: File -> f.name.endsWith(".xml") }
            .orEmpty()
            .map { it.name.removePrefix("TEST-").removeSuffix(".xml").substringAfterLast('.') }
            .toSet()

    private val every = setOf(
        "A0BystanderTest", "A5CommonThriceTest", "B1DefaultTest", "B2JvmOnlyTest", "B3QuadTest",
        "B4LimitTest", "B5PlatformTest", "B6PlainTest",
    )

    /**
     * Narrowed to exactly [expected]: the tests that reach the change, and every test after the
     * first of them in name order, since those share the test JVM with the changed class. The first
     * test runs only a class no change touches, so it is never among them.
     */
    private fun assertNarrowed(dir: File, expected: Set<String>, output: String) {
        assertEquals("narrowed", decisionNotes(dir)["outcome"], output)
        assertEquals(expected, ran(dir), output)
    }

    private fun assertFullRun(dir: File, kind: String, output: String) {
        assertEquals("full-run", decisionNotes(dir)["outcome"], output)
        assertEquals(kind, decisionNotes(dir)["full-run-kind"], output)
        assertEquals(every, ran(dir), output)
    }

    @Test
    fun `a capture scopes the module's commonMain and jvmMain packages`(@TempDir dir: File) {
        multiplatform(dir)

        val output = runner(dir, "jvmTest").build().output

        assertContains(output, ":jvmTest map updated")
        val mapDir = File(dir, ".gradle/yoriwake").listFiles()!!.single(File::isDirectory)
        val scope = File(mapDir, "effective-scope").readLines()
        assertTrue("include\tdev.common.*" in scope, "commonMain's package is not in scope: $scope")
        assertTrue("include\tdev.jvm.*" in scope, "jvmMain's package is not in scope: $scope")
        // The changed-bytes rule reads this table; an `expect` default's change depends on it.
        assertTrue(File(mapDir, "class-digests").readLines().any { it.startsWith("dev.common.Api_jvmKt") },
            "the map holds no digest for the class an expect default compiles into")
        runner(dir, "yoriwakeAuditJvmTest").build()
        assertFalse(File(mapDir, "audit.json").readText().contains("\"token\""),
            "the audit reports a blocker: ${File(mapDir, "audit.json").readText()}")
    }

    @Test
    fun `a jvmMain change runs the tests that ran it`(@TempDir dir: File) {
        multiplatform(dir)

        val output = selectAfter(dir, edit = {
            edit(dir, "src/jvmMain/kotlin/dev/jvm/JvmOnly.kt", "name() = \"jvm\"", "name() = \"j\" + \"vm\"")
        })

        assertNarrowed(dir, setOf("B2JvmOnlyTest", "B3QuadTest", "B4LimitTest", "B5PlatformTest", "B6PlainTest"), output)
    }

    @Test
    fun `a commonMain change runs the tests that ran it`(@TempDir dir: File) {
        multiplatform(dir)

        val output = selectAfter(dir, edit = {
            edit(dir, "src/commonMain/kotlin/dev/common/Shared.kt", "thrice(n: Int) = n * 3", "thrice(n: Int) = n + n + n")
        })

        assertNarrowed(dir, every - "A0BystanderTest", output)
    }

    @Test
    fun `an edited commonTest class runs, beside the tests that share its JVM`(@TempDir dir: File) {
        multiplatform(dir)

        val output = selectAfter(dir, edit = {
            edit(dir, "src/commonTest/kotlin/dev/common/A5CommonThriceTest.kt",
                "assertEquals(6, thrice(2))", "assertEquals(6, thrice(2), \"thrice\")")
        })

        // Discovery loads every test class into the one test JVM, the edited one too: the first
        // test's record holds what discovery loaded, so it reaches the edit, and every later test
        // shares the JVM with it.
        assertEquals("narrowed", decisionNotes(dir)["outcome"], output)
        val reasons = decisionReasons(dir).mapKeys { (id, _) -> id.substringAfter("[class:").substringBefore("]") }
        assertEquals("REACHES_CHANGE", reasons["dev.common.A5CommonThriceTest"], reasons.toString())
        assertEquals("REACHES_CHANGE", reasons["dev.common.A0BystanderTest"], reasons.toString())
        assertEquals(every, ran(dir), output)
    }

    @Test
    fun `a changed expect default runs its caller through the compiled class that changed`(@TempDir dir: File) {
        multiplatform(dir)

        // The default's bytes live in the actual's class, which no changed path names; only the
        // class-digest comparison finds them. The caller fails on the new default, so leaving it
        // out would show as a passing build.
        val output = selectAfter(dir, edit = {
            edit(dir, "src/commonMain/kotlin/dev/common/Api.kt", "x: Int = 1", "x: Int = 2")
        }, run = GradleRunner::buildAndFail)

        assertNarrowed(dir, every - "A0BystanderTest" - "A5CommonThriceTest", output)
        assertContains(output, "B1DefaultTest")
        assertContains(output, "FAILED")
    }

    @Test
    fun `a changed inline function in commonMain runs everything`(@TempDir dir: File) {
        multiplatform(dir)

        // Its callers are found, but the function's own class never runs, so no test covers it.
        val output = selectAfter(dir, edit = {
            edit(dir, "src/commonMain/kotlin/dev/common/Inlines.kt", "n * 4", "n + n + n + n")
        })

        assertFullRun(dir, "no-coverage-for-changed", output)
    }

    @Test
    fun `a changed const val in commonMain runs everything`(@TempDir dir: File) {
        multiplatform(dir)

        val output = selectAfter(dir, edit = {
            edit(dir, "src/commonMain/kotlin/dev/common/Limits.kt", "LIMIT = 5", "LIMIT = 6")
        }, run = GradleRunner::buildAndFail)

        assertFullRun(dir, "daemon-refused", output)
    }

    @Test
    fun `a change to a platform-suffixed file runs everything`(@TempDir dir: File) {
        multiplatform(dir)

        // Platform.jvm.kt compiles to Platform_jvmKt, not the Platform.jvm its path names.
        val output = selectAfter(dir, edit = {
            edit(dir, "src/jvmMain/kotlin/dev/jvm/Platform.jvm.kt", "\"JVM\"", "\"J\" + \"VM\"")
        })

        assertFullRun(dir, "no-coverage-for-changed", output)
    }

    @Test
    fun `a change in a non-JVM source set runs every JVM test`(@TempDir dir: File) {
        multiplatform(dir)

        val output = selectAfter(dir, edit = {
            edit(dir, "src/jsMain/kotlin/dev/common/JsOnly.kt", "\"js\"", "\"j\" + \"s\"")
        })

        assertFullRun(dir, "no-coverage-for-changed", output)
    }

    @Test
    fun `two selecting runs with the configuration cache on each see their own change`(@TempDir dir: File) {
        multiplatform(dir)
        runner(dir, "jvmTest").build()

        edit(dir, "src/jvmMain/kotlin/dev/jvm/JvmOnly.kt", "name() = \"jvm\"", "name() = \"j\" + \"vm\"")
        commit(dir, "jvm change")
        File(dir, "build/test-results").deleteRecursively()
        val first = runner(dir, "jvmTest", "-Pyoriwake.select", "-Pyoriwake.base=HEAD~1").build().output
        assertNarrowed(dir, setOf("B2JvmOnlyTest", "B3QuadTest", "B4LimitTest", "B5PlatformTest", "B6PlainTest"), first)

        edit(dir, "src/commonMain/kotlin/dev/common/Shared.kt", "thrice(n: Int) = n * 3", "thrice(n: Int) = n + n + n")
        File(dir, "build/test-results").deleteRecursively()
        val second = runner(dir, "jvmTest", "-Pyoriwake.select", "-Pyoriwake.base=HEAD~1").build().output

        assertNarrowed(dir, every - "A0BystanderTest", second)
    }

    /**
     * `:shared` (KMP, `jvm()`), `:app` (KMP, depends on it), `:consumer` (`java-library`, depends on
     * it), `:desktop` (a JVM target named `desktop`, so no `jvm*` directory) and `:web` (no JVM
     * target, on no test classpath). Each has its own package.
     */
    private fun modules(dir: File): File {
        val jvm = "jvm { testRuns[\"test\"].executionTask.configure { useJUnitPlatform() } }"
        fun kmp(targets: String, dependency: String = "") = """
            plugins {
                kotlin("multiplatform")
                jacoco
                id("io.github.zeuspizza.yoriwake")
            }
            repositories { mavenCentral() }
            kotlin {
                jvmToolchain(21)
                $targets
                sourceSets {
                    commonMain.dependencies { $dependency }
                    commonTest.dependencies { implementation(kotlin("test")) }
                }
            }
        """.trimIndent()
        fun test(path: String, pkg: String, name: String, assertion: String) = "$path/$name.kt" to """
            package $pkg
            import kotlin.test.Test
            import kotlin.test.assertEquals
            class $name { @Test fun passes() { $assertion } }
        """.trimIndent()
        build(
            dir,
            "build.gradle.kts" to """plugins { kotlin("multiplatform") version "2.1.20" apply false }""",
            "shared/build.gradle.kts" to kmp(jvm),
            "shared/src/commonMain/kotlin/dev/shared/Shared.kt" to """
                package dev.shared
                fun twice(n: Int) = n * 2
                fun thrice(n: Int) = n * 3
                fun names() = listOf(1, 2).map { it.toString() }
            """.trimIndent(),
            "app/build.gradle.kts" to kmp(jvm, "implementation(project(\":shared\"))"),
            "app/src/commonMain/kotlin/dev/app/App.kt" to """
                package dev.app
                fun appTwice(n: Int) = dev.shared.twice(n)
                fun own() = 1
            """.trimIndent(),
            test("app/src/jvmTest/kotlin/dev/app", "dev.app", "A0OwnTest", "assertEquals(1, own())"),
            test("app/src/jvmTest/kotlin/dev/app", "dev.app", "A1TwiceTest", "assertEquals(6, appTwice(3))"),
            "app/src/jvmTest/resources/junit-platform.properties" to classOrderByName.second,
            "consumer/build.gradle.kts" to """
                plugins {
                    `java-library`
                    jacoco
                    id("io.github.zeuspizza.yoriwake")
                }
                repositories { mavenCentral() }
                dependencies {
                    implementation(project(":shared"))
                    testImplementation(platform("org.junit:junit-bom:5.11.4"))
                    testImplementation("org.junit.jupiter:junit-jupiter")
                    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
                }
                java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }
                tasks.test { useJUnitPlatform() }
            """.trimIndent(),
            "consumer/src/main/java/dev/consumer/Uses.java" to """
                package dev.consumer;
                public class Uses {
                    public static int thrice(int n) { return dev.shared.SharedKt.thrice(n); }
                    public static int own() { return 2; }
                }
            """.trimIndent(),
            "consumer/src/test/java/dev/consumer/A0OwnTest.java" to """
                package dev.consumer;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertEquals;
                class A0OwnTest { @Test void passes() { assertEquals(2, Uses.own()); } }
            """.trimIndent(),
            "consumer/src/test/java/dev/consumer/A1ThriceTest.java" to """
                package dev.consumer;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertEquals;
                class A1ThriceTest { @Test void passes() { assertEquals(6, Uses.thrice(2)); } }
            """.trimIndent(),
            "consumer/src/test/resources/junit-platform.properties" to classOrderByName.second,
            "desktop/build.gradle.kts" to kmp("jvm(\"desktop\") { testRuns[\"test\"].executionTask.configure { useJUnitPlatform() } }"),
            "desktop/src/commonMain/kotlin/dev/desktop/Desk.kt" to "package dev.desktop\nfun desk() = 3\n",
            test("desktop/src/desktopTest/kotlin/dev/desktop", "dev.desktop", "DeskTest", "assertEquals(3, desk())"),
            "web/build.gradle.kts" to kmp("js { nodejs() }"),
            "web/src/commonMain/kotlin/dev/web/Web.kt" to "package dev.web\nfun web() = 4\n",
        )
        File(dir, "settings.gradle.kts").writeText(
            """
            pluginManagement { repositories { mavenCentral(); gradlePluginPortal() } }
            rootProject.name = "sample"
            include("shared", "app", "consumer", "desktop", "web")
            """.trimIndent()
        )
        ignoreBuildOutputs(dir)
        File(dir, ".gitignore").appendText(System.lineSeparator() + "**/build/" + System.lineSeparator() + ".kotlin/")
        git(dir, "init")
        commit(dir, "base")
        return dir
    }

    private val moduleTasks = arrayOf(":app:jvmTest", ":consumer:test", ":desktop:desktopTest")

    private fun notes(dir: File, task: String): Map<String, String> {
        val mapDir = File(dir, ".gradle/yoriwake").listFiles()!!.single { it.name.startsWith("$task-") }
        return File(mapDir, "decisions.tsv").readLines()
            .filter { it.startsWith("#!") }
            .associate { line ->
                val (key, value) = line.removePrefix("#!").split("\t", limit = 2)
                key to value
            }
    }

    @Test
    fun `a commonMain change in one module runs the covering tests of the modules that depend on it`(
        @TempDir dir: File,
    ) {
        modules(dir)
        runner(dir, *moduleTasks).build()
        edit(dir, "shared/src/commonMain/kotlin/dev/shared/Shared.kt", "thrice(n: Int) = n * 3", "thrice(n: Int) = n + n + n")
        File(dir, "consumer/build/test-results").deleteRecursively()
        File(dir, "app/build/test-results").deleteRecursively()

        val output = runner(dir, *moduleTasks, "-Pyoriwake.select", "-Pyoriwake.base=HEAD").build().output

        assertEquals("narrowed", notes(dir, "consumer-test")["outcome"], output)
        assertEquals(setOf("A1ThriceTest"), ran(File(dir, "consumer"), "test"), output)
        // Coverage is by class: `:app`'s test of `twice` ran the class `thrice` is in.
        assertEquals("narrowed", notes(dir, "app-jvmTest")["outcome"], output)
        assertEquals(setOf("A1TwiceTest"), ran(File(dir, "app"), "jvmTest"), output)
    }

    @Test
    fun `a JVM target with its own name is declined, and its tests and its changes run everything`(
        @TempDir dir: File,
    ) {
        modules(dir)

        val capture = runner(dir, *moduleTasks).build().output
        assertContains(capture, ":desktop: this is a Kotlin Multiplatform module with no JVM target")
        assertTrue(File(dir, "desktop/build/test-results/desktopTest/TEST-dev.desktop.DeskTest.xml").isFile, capture)
        runner(dir, ":desktop:yoriwakeAuditDesktopTest").build()
        val audit = File(dir, ".gradle/yoriwake").listFiles()!!.single { it.name.startsWith("desktop-") }
        assertContains(File(audit, "audit.json").readText(), """"token": "kotlin-multiplatform"""")

        // The declined module's package is still in the build's scope, so its change is one no
        // class answers to: every other task runs in full rather than guessing it is unrelated.
        edit(dir, "desktop/src/commonMain/kotlin/dev/desktop/Desk.kt", "desk() = 3", "desk() = 1 + 2")
        val output = runner(dir, *moduleTasks, "-Pyoriwake.select", "-Pyoriwake.base=HEAD").build().output
        for (task in listOf("app-jvmTest", "consumer-test")) {
            assertEquals("full-run", notes(dir, task)["outcome"], output)
            assertEquals("no-coverage-for-changed", notes(dir, task)["full-run-kind"], output)
        }
    }

    @Test
    fun `a change in a module with no JVM target runs every JVM test`(@TempDir dir: File) {
        modules(dir)
        runner(dir, *moduleTasks).build()
        edit(dir, "web/src/commonMain/kotlin/dev/web/Web.kt", "web() = 4", "web() = 2 + 2")

        val output = runner(dir, *moduleTasks, "-Pyoriwake.select", "-Pyoriwake.base=HEAD").build().output

        for (task in listOf("app-jvmTest", "consumer-test")) {
            assertEquals("full-run", notes(dir, task)["outcome"], output)
            assertEquals("no-coverage-for-changed", notes(dir, task)["full-run-kind"], output)
        }
    }
}
