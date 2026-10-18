package io.github.zeuspizza.yoriwake.gradle

import org.gradle.testkit.runner.GradleRunner
import java.io.File
import kotlin.test.assertEquals

/**
 * Drives a real Gradle build with the plugin applied.
 *
 * Extensions looked up across a classloader boundary, or settings overwritten by the host's own
 * conventions, only show up when a real build evaluates and a real task executes.
 *
 * The fixtures and helpers every feature's functional test shares; each feature is its own class.
 */
abstract class FunctionalTestSupport {

    protected fun build(dir: File, vararg sources: Pair<String, String>): File {
        File(dir, "settings.gradle.kts").writeText("""rootProject.name = "sample"""")
        sources.forEach { (path, content) ->
            File(dir, path).parentFile.mkdirs()
            File(dir, path).writeText(content)
        }
        return dir
    }

    /**
     * Every functional build runs with the configuration cache on, as most host builds worth
     * speeding up do, so a cache violation fails here rather than in someone's build.
     *
     * File-system watching is off because these tests edit a source and start the next build within
     * the watcher's delivery latency on macOS; the daemon would then trust its old snapshot and
     * compile nothing.
     */
    protected fun runner(dir: File, vararg args: String) =
        GradleRunner.create()
            .withProjectDir(dir)
            .withPluginClasspath()
            .withGradleVersion(FunctionalGradle.version)
            .withTestKitDir(FunctionalGradle.testKitDir())
            .withArguments(*args, "--configuration-cache", "--stacktrace", "--no-watch-fs")
            .forwardOutput()

    protected val minimalBuild = """
        plugins {
            java
            jacoco
            id("io.github.zeuspizza.yoriwake")
        }
        repositories { mavenCentral() }
        dependencies {
            testImplementation(platform("org.junit:junit-bom:5.11.4"))
            testImplementation("org.junit.jupiter:junit-jupiter")
            testRuntimeOnly("org.junit.platform:junit-platform-launcher")
        }
        tasks.test { useJUnitPlatform() }
    """.trimIndent()

    protected val kotlinBuild = """
        plugins {
            kotlin("jvm") version "2.1.20"
            jacoco
            id("io.github.zeuspizza.yoriwake")
        }
        repositories { mavenCentral() }
        dependencies {
            testImplementation(platform("org.junit:junit-bom:5.11.4"))
            testImplementation("org.junit.jupiter:junit-jupiter")
            testImplementation(kotlin("test"))
            testRuntimeOnly("org.junit.platform:junit-platform-launcher")
        }
        // Kotlin 2.1.20 targets at most Java 23, so the fixture keeps to 21 on any daemon JDK.
        kotlin { jvmToolchain(21) }
        tasks.test { useJUnitPlatform() }
    """.trimIndent()

    protected val oneTest = "src/test/java/dev/sample/AlphaTest.java" to """
        package dev.sample;
        import org.junit.jupiter.api.Test;
        import static org.junit.jupiter.api.Assertions.assertEquals;
        class AlphaTest {
            @Test void passes() { assertEquals(2, new Alpha().twice(1)); }
        }
    """.trimIndent()

    protected val oneClass = "src/main/java/dev/sample/Alpha.java" to """
        package dev.sample;
        public class Alpha { public int twice(int n) { return n * 2; } }
    """.trimIndent()

    protected val secondClass = "src/main/java/dev/sample/Beta.java" to """
        package dev.sample;
        public class Beta { public int thrice(int n) { return n * 3; } }
    """.trimIndent()

    protected val secondTest = "src/test/java/dev/sample/BetaTest.java" to """
        package dev.sample;
        import org.junit.jupiter.api.Test;
        import static org.junit.jupiter.api.Assertions.assertEquals;
        class BetaTest {
            @Test void passes() { assertEquals(3, new Beta().thrice(1)); }
        }
    """.trimIndent()

    /**
     * Pins Jupiter's test-class order by name. A change selects every test that ran after its
     * class was first loaded in the same JVM, so which tests run depends on the order, and an
     * unpinned order is whatever the file system lists first.
     */
    protected val classOrderByName = "src/test/resources/junit-platform.properties" to
        "junit.jupiter.testclass.order.default=org.junit.jupiter.api.ClassOrderer\$ClassName\n"

    /** A changed Beta: BetaTest runs last by name, so it is the one change nothing before it sees. */
    protected fun changeBeta(dir: File) {
        File(dir, "src/main/java/dev/sample/Beta.java").writeText(
            """
            package dev.sample;
            public class Beta { public int thrice(int n) { return n + n + n; } }
            """.trimIndent()
        )
    }

    /** What every real project ignores. Without it, build outputs read as untracked changes. */
    protected fun ignoreBuildOutputs(dir: File) {
        File(dir, ".gitignore").writeText("build/" + System.lineSeparator() + ".gradle/")
    }

    protected fun git(dir: File, vararg args: String) {
        val exit = ProcessBuilder("git", *args)
            .directory(dir)
            .redirectErrorStream(true)
            // Discarded, not left unread: under autocrlf git warns once per file, and a pipe
            // nobody reads fills and blocks it for good.
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .start()
            .waitFor()
        assertEquals(0, exit, "git ${args.joinToString(" ")} failed")
    }

    protected fun ranTests(dir: File): Set<String> =
        File(dir, "build/test-results/test")
            .listFiles { f: File -> f.name.endsWith(".xml") }
            .orEmpty()
            .map { it.name.removePrefix("TEST-").removeSuffix(".xml") }
            .toSet()

    protected val junit4Build = """
        plugins {
            java
            jacoco
            id("io.github.zeuspizza.yoriwake")
        }
        repositories { mavenCentral() }
        dependencies { testImplementation("junit:junit:4.13.2") }
        tasks.test { useJUnit() }
    """.trimIndent()

    protected val junit4Tests = arrayOf(
        "src/test/java/dev/sample/AlphaTest.java" to """
            package dev.sample;
            import org.junit.Test;
            import static org.junit.Assert.assertEquals;
            public class AlphaTest {
                @Test public void passes() { assertEquals(2, new Alpha().twice(1)); }
            }
        """.trimIndent(),
        "src/test/java/dev/sample/BetaTest.java" to """
            package dev.sample;
            import org.junit.Test;
            import static org.junit.Assert.assertEquals;
            public class BetaTest {
                @Test public void passes() { assertEquals(3, new Beta().thrice(1)); }
            }
        """.trimIndent(),
    )

    protected val betaClass = "src/main/java/dev/sample/Beta.java" to """
        package dev.sample;
        public class Beta { public int thrice(int n) { return n * 3; } }
    """.trimIndent()

    /**
     * A committed Kotlin Multiplatform module with a `jvm()` and a `js()` target, at the root of
     * [dir]. `js()` needs no toolchain download for a JVM test build, so the module has a non-JVM
     * target without Node.js or Kotlin/Native on the machine.
     *
     * `commonMain` declares package `dev.common` and `jvmMain` package `dev.jvm`, so a scope that
     * leaves out either directory visibly lacks its package. `JvmOnly.usesQuad` inlines a
     * `commonMain` function, so the build's classes carry the `SourceDebugExtension` real Kotlin
     * code always has; without one, any Kotlin change forces a full run. Test classes run in name
     * order, and `A0BystanderTest` runs first and only a class no change touches, so a narrowed run
     * leaves it out. (A test that ran no class at all would run on every build.) Tests share their
     * code's package: an edited Kotlin test outside every scoped package is a change no class answers
     * to, and runs everything.
     */
    protected fun multiplatform(dir: File, yoriwake: String = "id(\"io.github.zeuspizza.yoriwake\")"): File {
        fun test(name: String, assertion: String, sourceSet: String = "jvmTest") =
            "src/$sourceSet/kotlin/dev/common/$name.kt" to """
                package dev.common
                import dev.jvm.*
                import kotlin.test.Test
                import kotlin.test.assertEquals
                class $name { @Test fun passes() { $assertion } }
            """.trimIndent()
        build(
            dir,
            "build.gradle.kts" to """
                plugins {
                    kotlin("multiplatform") version "2.1.20"
                    jacoco
                    $yoriwake
                }
                repositories { mavenCentral() }
                kotlin {
                    // Kotlin 2.1.20 targets at most Java 23, so the fixture keeps to 21 on any daemon JDK.
                    jvmToolchain(21)
                    jvm { testRuns["test"].executionTask.configure { useJUnitPlatform() } }
                    js { nodejs() }
                    sourceSets { commonTest.dependencies { implementation(kotlin("test")) } }
                }
            """.trimIndent(),
            "src/commonMain/kotlin/dev/common/Shared.kt" to """
                package dev.common
                fun twice(n: Int) = n * 2
                fun thrice(n: Int) = n * 3
            """.trimIndent(),
            "src/commonMain/kotlin/dev/common/Api.kt" to """
                package dev.common
                expect fun f(x: Int = 1): Int
                fun plain() = 7
            """.trimIndent(),
            "src/jvmMain/kotlin/dev/common/Api.jvm.kt" to """
                package dev.common
                actual fun f(x: Int): Int = x + 100
            """.trimIndent(),
            "src/jsMain/kotlin/dev/common/Api.js.kt" to """
                package dev.common
                actual fun f(x: Int): Int = x + 100
            """.trimIndent(),
            "src/jsMain/kotlin/dev/common/JsOnly.kt" to """
                package dev.common
                fun jsName() = "js"
            """.trimIndent(),
            "src/commonMain/kotlin/dev/common/Inlines.kt" to """
                package dev.common
                inline fun quad(n: Int) = n * 4
            """.trimIndent(),
            "src/commonMain/kotlin/dev/common/Bystander.kt" to """
                package dev.common
                fun bystander() = 0
            """.trimIndent(),
            "src/commonMain/kotlin/dev/common/Limits.kt" to """
                package dev.common
                const val LIMIT = 5
            """.trimIndent(),
            "src/jvmMain/kotlin/dev/jvm/JvmOnly.kt" to """
                package dev.jvm
                import dev.common.quad
                class JvmOnly {
                    fun name() = "jvm"
                    fun usesQuad(n: Int) = quad(n)
                }
            """.trimIndent(),
            "src/jvmMain/kotlin/dev/jvm/Platform.jvm.kt" to """
                package dev.jvm
                fun platformName() = "JVM"
            """.trimIndent(),
            test("A0BystanderTest", "assertEquals(0, bystander())"),
            test("A5CommonThriceTest", "assertEquals(6, thrice(2))", sourceSet = "commonTest"),
            test("B1DefaultTest", "assertEquals(101, f())"),
            test("B2JvmOnlyTest", "assertEquals(\"jvm\", JvmOnly().name())"),
            test("B3QuadTest", "assertEquals(8, JvmOnly().usesQuad(2))"),
            test("B4LimitTest", "assertEquals(5, LIMIT)"),
            test("B5PlatformTest", "assertEquals(\"JVM\", platformName())"),
            test("B6PlainTest", "assertEquals(7, plain())"),
            "src/jvmTest/resources/junit-platform.properties" to classOrderByName.second,
        )
        File(dir, "settings.gradle.kts").writeText(
            "pluginManagement { repositories { mavenCentral(); gradlePluginPortal() } }\n" +
                """rootProject.name = "sample""""
        )
        ignoreBuildOutputs(dir)
        // Where Kotlin's compiler keeps its session state; a Kotlin project ignores it.
        File(dir, ".gitignore").appendText(System.lineSeparator() + ".kotlin/")
        git(dir, "init")
        commit(dir, "base")
        return dir
    }

    /** The notes a decision record carries about the run as a whole, as `key` to `value`. */
    protected fun decisionNotes(dir: File): Map<String, String> {
        val mapDir = File(dir, ".gradle/yoriwake").listFiles()!!.single(File::isDirectory)
        return File(mapDir, "decisions.tsv").readLines()
            .filter { it.startsWith("#!") }
            .associate { line ->
                val (key, value) = line.removePrefix("#!").split("\t", limit = 2)
                key to value
            }
    }

    /** Each test's reason column in the decision record, keyed by test id. */
    protected fun decisionReasons(dir: File): Map<String, String> {
        val mapDir = File(dir, ".gradle/yoriwake").listFiles()!!.single(File::isDirectory)
        return File(mapDir, "decisions.tsv").readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.split("\t") }
            .associate { it[0] to it[2] }
    }

    /** A committed two-class sample with a captured map and an uncommitted change to Alpha. */
    protected fun capturedWithAlphaChanged(
        dir: File,
        buildScript: String,
        vararg tests: Pair<String, String>,
    ) {
        build(dir, "build.gradle.kts" to buildScript, oneClass, secondClass, *tests)
        ignoreBuildOutputs(dir)
        git(dir, "init")
        git(dir, "-c", "user.email=t@example.com", "-c", "user.name=t", "commit",
            "--allow-empty", "-m", "base")
        git(dir, "add", ".")
        git(dir, "-c", "user.email=t@example.com", "-c", "user.name=t", "commit", "-m", "sample")
        runner(dir, "test").build()
        File(dir, "src/main/java/dev/sample/Alpha.java").writeText(
            """
            package dev.sample;
            public class Alpha { public int twice(int n) { return n + n; } }
            """.trimIndent()
        )
    }

    protected fun commit(dir: File, message: String) {
        git(dir, "add", ".")
        git(dir, "-c", "user.email=t@example.com", "-c", "user.name=t", "commit", "-m", message)
    }

    /** A fresh repository with one commit holding everything written so far. */
    protected fun committed(dir: File) {
        ignoreBuildOutputs(dir)
        git(dir, "init")
        commit(dir, "base")
    }

    /**
     * The JDKs the build was told about, and only those: a TestKit build has its own Gradle home,
     * and on CI runners auto-detection misses where the JDKs are installed.
     */
    protected fun toolchains(dir: File) {
        val paths = System.getProperty("yoriwake.functional.toolchainPaths").orEmpty()
        val lines = mutableListOf("org.gradle.java.installations.auto-download=false")
        if (paths.isNotEmpty()) {
            lines += "org.gradle.java.installations.auto-detect=false"
            lines += "org.gradle.java.installations.paths=" + paths.replace("\\", "\\\\")
        }
        File(dir, "gradle.properties").writeText(lines.joinToString("\n", postfix = "\n"))
    }
}
