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
}
