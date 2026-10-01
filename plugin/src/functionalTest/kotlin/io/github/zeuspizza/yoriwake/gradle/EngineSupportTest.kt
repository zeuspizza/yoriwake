package io.github.zeuspizza.yoriwake.gradle

import io.github.zeuspizza.yoriwake.agent.contract.AgentContract
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the tool does on test engines it was not built against.
 *
 * The capture listener and the selection filter are both found by `ServiceLoader` from the JUnit
 * Platform. An engine that runs on the Platform gets both for free. An engine that does not — TestNG
 * — gets neither, and the tool then captures nothing, builds no map, and lets every run proceed
 * normally. Safe, and indistinguishable from working.
 *
 * These tests make that distinguishable: each asserts records reach the map.
 */
class EngineSupportTest {

    private fun runner(dir: File, vararg args: String) =
        GradleRunner.create()
            .withProjectDir(dir)
            .withPluginClasspath()
            .withGradleVersion(FunctionalGradle.version)
            .withTestKitDir(FunctionalGradle.testKitDir())
            .withArguments(*args, "--configuration-cache", "--stacktrace", "--no-watch-fs")
            .forwardOutput()

    private fun project(dir: File, dependencies: String, testConfig: String, vararg sources: Pair<String, String>) {
        File(dir, "settings.gradle.kts").writeText("""rootProject.name = "engine"""")
        File(dir, "build.gradle.kts").writeText(
            """
            plugins {
                java
                jacoco
                id("io.github.zeuspizza.yoriwake")
            }
            repositories { mavenCentral() }
            dependencies {
            $dependencies
            }
            tasks.test { $testConfig }
            """.trimIndent()
        )
        sources.forEach { (path, content) ->
            File(dir, path).parentFile.mkdirs()
            File(dir, path).writeText(content)
        }
    }

    private val subject = "src/main/java/dev/engine/Subject.java" to """
        package dev.engine;
        public class Subject {
            public int twice(int n) { return n * 2; }
        }
    """.trimIndent()

    @Test
    fun `a JUnit 4 suite on the vintage engine is captured`(@TempDir dir: File) {
        project(
            dir,
            """
                testImplementation(platform("org.junit:junit-bom:5.11.4"))
                testImplementation("junit:junit:4.13.2")
                testRuntimeOnly("org.junit.vintage:junit-vintage-engine")
                testRuntimeOnly("org.junit.platform:junit-platform-launcher")
            """.trimIndent(),
            "useJUnitPlatform()",
            subject,
            "src/test/java/dev/engine/SubjectTest.java" to """
                package dev.engine;
                import org.junit.Test;
                import static org.junit.Assert.assertEquals;
                public class SubjectTest {
                    @Test public void doubles() { assertEquals(2, new Subject().twice(1)); }
                }
            """.trimIndent(),
        )

        val output = runner(dir, "test").build().output

        assertContains(output, "map updated:")
        val map = File(dir, ".gradle/yoriwake").listFiles()?.singleOrNull(File::isDirectory)
        assertTrue(map != null && File(map, "coverage.tsv").readText().contains("dev.engine.Subject"))
    }

    private fun git(dir: File, vararg args: String) {
        val exit = ProcessBuilder("git", "-c", "user.email=t@example.com", "-c", "user.name=t", *args)
            .directory(dir).redirectErrorStream(true).start().waitFor()
        assertEquals(0, exit, "git ${args.joinToString(" ")} failed")
    }

    private fun ranTests(dir: File): Set<String> =
        File(dir, "build/test-results/test").listFiles { f: File -> f.name.endsWith(".xml") }.orEmpty()
            .map { it.name.removePrefix("TEST-").removeSuffix(".xml") }.toSet()

    /**
     * Captures, asserts the map holds the subjects' coverage, then changes the subject only the
     * spec that ran last reaches, and asserts a selecting run judges every spec from the map and
     * runs that spec, plus [alsoRuns]. The last, because a change also selects every test that ran
     * after its class was first loaded in the same JVM, and an engine's spec order is its own.
     *
     * [changes] maps each spec's simple name to its subject's path and a changed source for it.
     */
    private fun assertCapturedAndSelected(
        dir: File,
        changes: Map<String, Pair<String, String>>,
        alsoRuns: Set<String> = emptySet(),
    ) {
        File(dir, ".gitignore").writeText("build/\n.gradle/\n.kotlin/\n")
        git(dir, "init")
        git(dir, "commit", "--allow-empty", "-m", "base")
        git(dir, "add", ".")
        git(dir, "commit", "-m", "sample")

        val output = runner(dir, "test").build().output

        val map = File(dir, ".gradle/yoriwake").listFiles()?.singleOrNull(File::isDirectory)
        assertTrue(
            map != null && File(map, "coverage.tsv").readText().contains("dev.engine.Alpha"),
            "coverage was not captured; output: $output",
        )
        val positions = File(map, AgentContract.POSITIONS_FILE).readLines().filter(String::isNotBlank)
            .map { it.split('\t') }
        val last = changes.keys.maxBy { spec ->
            positions.filter { it[2].contains(".$spec") }.maxOfOrNull { it[1].toInt() }
                ?: error("$spec has no position in the map: $positions")
        }
        val (path, changed) = changes.getValue(last)
        File(dir, path).writeText(changed)
        File(dir, "build/test-results").deleteRecursively()

        val selecting = runner(dir, "test", "-Pyoriwake.select").build().output

        assertContains(selecting, "selecting against")
        val decisions = File(map, AgentContract.DECISIONS_FILE).readText()
        assertContains(decisions, "#!outcome\tnarrowed", message = "no spec was judged from the map")
        assertEquals(
            (alsoRuns + last).map { "dev.engine.$it" }.toSet(), ranTests(dir),
            "the selecting run ran another set of specs: $decisions",
        )
    }

    @Test
    fun `a Spock specification is captured and selected`(@TempDir dir: File) {
        // Spock 2 runs on the JUnit Platform, so ServiceLoader should find the listener and filter.
        File(dir, "settings.gradle.kts").writeText("""rootProject.name = "engine"""")
        File(dir, "build.gradle.kts").writeText(
            """
            plugins {
                groovy
                jacoco
                id("io.github.zeuspizza.yoriwake")
            }
            repositories { mavenCentral() }
            dependencies {
                testImplementation("org.spockframework:spock-core:2.3-groovy-4.0")
                testImplementation("org.apache.groovy:groovy:4.0.22")
                testRuntimeOnly("org.junit.platform:junit-platform-launcher")
            }
            tasks.test { useJUnitPlatform() }
            """.trimIndent()
        )
        val subject = { name: String, body: String ->
            "src/main/java/dev/engine/$name.java" to """
                package dev.engine;
                public class $name {
                    public int twice(int n) { return $body; }
                }
            """.trimIndent()
        }
        for (name in listOf("Alpha", "Beta")) {
            val (path, source) = subject(name, "n * 2")
            File(dir, path).apply { parentFile.mkdirs() }.writeText(source)
            File(dir, "src/test/groovy/dev/engine/${name}Spec.groovy").apply { parentFile.mkdirs() }
                .writeText(
                    """
                    package dev.engine

                    import spock.lang.Specification

                    class ${name}Spec extends Specification {
                        def "doubles"() {
                            expect:
                            new $name().twice(1) == 2
                        }
                    }
                    """.trimIndent()
                )
        }

        assertCapturedAndSelected(dir, listOf("Alpha", "Beta").associate { "${it}Spec" to subject(it, "n + n") })
    }

    /** Two Kotest specs, each reaching only its own subject; returns a writer of a subject's source. */
    private fun kotestProject(dir: File, testConfig: String = ""): (String, String) -> Pair<String, String> {
        // Kotest ships its own JUnit Platform engine rather than running on Jupiter.
        File(dir, "settings.gradle.kts").writeText("""rootProject.name = "engine"""")
        File(dir, "build.gradle.kts").writeText(
            """
            plugins {
                kotlin("jvm") version "2.1.20"
                jacoco
                id("io.github.zeuspizza.yoriwake")
            }
            repositories { mavenCentral() }
            dependencies {
                testImplementation("io.kotest:kotest-runner-junit5:5.9.1")
                testRuntimeOnly("org.junit.platform:junit-platform-launcher")
            }
            kotlin { jvmToolchain(21) }
            tasks.test { useJUnitPlatform(); $testConfig }
            """.trimIndent()
        )
        val subject = { name: String, body: String ->
            "src/main/kotlin/dev/engine/$name.kt" to """
                package dev.engine

                class $name {
                    fun twice(n: Int) = same($body)
                }
            """.trimIndent()
        }
        // A Kotlin build in which no class calls an inline function carries no SMAP, and selection
        // then refuses; that refusal is not what this test is about.
        File(dir, "src/main/kotlin/dev/engine/Same.kt").apply { parentFile.mkdirs() }
            .writeText("package dev.engine\n\ninline fun same(n: Int) = n\n")
        for (name in listOf("Alpha", "Beta")) {
            val (path, source) = subject(name, "n * 2")
            File(dir, path).apply { parentFile.mkdirs() }.writeText(source)
            File(dir, "src/test/kotlin/dev/engine/${name}Spec.kt").apply { parentFile.mkdirs() }
                .writeText(
                    """
                    package dev.engine

                    import io.kotest.core.spec.style.StringSpec
                    import io.kotest.matchers.shouldBe

                    class ${name}Spec : StringSpec({
                        "doubles" { $name().twice(1) shouldBe 2 }
                    })
                    """.trimIndent()
                )
        }
        return subject
    }

    @Test
    fun `a Kotest spec is captured and selected`(@TempDir dir: File) {
        val subject = kotestProject(dir)

        // Both specs run: Kotest's discovery scans the test classpath, reading every class file,
        // which dates each class from the start of the JVM, so a change selects every spec after it.
        assertCapturedAndSelected(
            dir,
            listOf("Alpha", "Beta").associate { "${it}Spec" to subject(it, "n + n") },
            alsoRuns = setOf("AlphaSpec", "BetaSpec"),
        )
    }

    @Test
    fun `a Kotest suite with classpath scanning off still runs every spec under selection`(@TempDir dir: File) {
        // Without scanning, a change reaches one spec only. Kotest runs its own spec list rather
        // than the filtered plan, and runs nothing at all once one spec is excluded, green.
        val subject = kotestProject(
            dir,
            """systemProperty("kotest.framework.classpath.scanning.config.disable", "true"); """ +
                """systemProperty("kotest.framework.classpath.scanning.autoscan.disable", "true")""",
        )
        File(dir, ".gitignore").writeText("build/\n.gradle/\n.kotlin/\n")
        git(dir, "init")
        git(dir, "commit", "--allow-empty", "-m", "base")
        git(dir, "add", ".")
        git(dir, "commit", "-m", "sample")
        runner(dir, "test").build()
        val map = checkNotNull(File(dir, ".gradle/yoriwake").listFiles()?.singleOrNull(File::isDirectory))

        val (path, changed) = subject("Beta", "n + n")
        File(dir, path).writeText(changed)
        File(dir, "build/test-results").deleteRecursively()
        val selected = runner(dir, "test", "-Pyoriwake.select").build().output

        val decisions = File(map, AgentContract.DECISIONS_FILE).readText()
        assertEquals(setOf("dev.engine.AlphaSpec", "dev.engine.BetaSpec"), ranTests(dir), decisions)
        assertContains(decisions, "[engine:kotest]/[spec:dev.engine.AlphaSpec]\tincluded\tENGINE_RUNS_EVERYTHING")
        assertContains(selected, "engine-runs-everything=2")

        val (_, broken) = subject("Beta", "n * 3")
        File(dir, path).writeText(broken)
        val failing = runner(dir, "test", "-Pyoriwake.select").buildAndFail().output
        assertContains(failing, "BetaSpec")
    }

    @Test
    fun `plain JUnit 4, without the platform, is captured by the bytecode hook`(@TempDir dir: File) {
        // Gradle's own JUnit 4 runner never touches the JUnit Platform, so the ServiceLoader listener
        // is never found. Capture comes from the agent rewriting RunNotifier instead.
        project(
            dir,
            """
                testImplementation("junit:junit:4.13.2")
            """.trimIndent(),
            "useJUnit()",
            subject,
            "src/test/java/dev/engine/SubjectTest.java" to """
                package dev.engine;
                import org.junit.Test;
                import static org.junit.Assert.assertEquals;
                public class SubjectTest {
                    @Test public void doubles() { assertEquals(2, new Subject().twice(1)); }
                }
            """.trimIndent(),
        )

        val result = runner(dir, "test").build()

        assertContains(result.output, "map updated:")
        // On the id, not a count: an id nothing matches does no work. Windows between tests are
        // recorded too, so the count is not one per test.
        val map = dir.walkTopDown().first { it.name == "coverage.tsv" }.readText()
        assertContains(map, "[engine:junit4]/[class:dev.engine.SubjectTest]/[method:doubles]")
    }
    @Test
    fun `a JUnit 4 test that runs another test keeps that coverage`(@TempDir dir: File) {
        // A JUnit 4 test may drive a nested run through JUnitCore, which fires the same RunNotifier
        // events. Treating those as boundaries would file the outer test's coverage under the inner
        // id, and a change to a class the outer test exercises would not select it.
        project(
            dir,
            """
                testImplementation("junit:junit:4.13.2")
            """.trimIndent(),
            "useJUnit()",
            subject,
            "src/test/java/dev/engine/InnerCase.java" to """
                package dev.engine;
                import org.junit.Test;
                import static org.junit.Assert.assertEquals;
                public class InnerCase {
                    @Test public void uses_subject() { assertEquals(2, new Subject().twice(1)); }
                }
            """.trimIndent(),
            "src/test/java/dev/engine/RunnerTest.java" to """
                package dev.engine;
                import org.junit.Test;
                import org.junit.runner.JUnitCore;
                import org.junit.runner.Result;
                import static org.junit.Assert.assertTrue;
                public class RunnerTest {
                    @Test public void runs_the_inner_case() {
                        Result result = JUnitCore.runClasses(InnerCase.class);
                        assertTrue(result.wasSuccessful());
                    }
                }
            """.trimIndent(),
        )

        runner(dir, "test").build()

        val map = dir.walkTopDown().first { it.name == "coverage.tsv" }.readText()
        val outer = map.lineSequence().firstOrNull {
            it.endsWith("[engine:junit4]/[class:dev.engine.RunnerTest]/[method:runs_the_inner_case]")
        }
        assertTrue(outer != null, "no record for the outer test at all; map was:\n$map")
        // The class only the nested run touches must be on the outer test's record.
        assertTrue(
            "dev.engine.Subject" in outer.orEmpty(),
            "the outer test's record must carry what its nested run executed; record was: $outer",
        )
    }

    @Test
    fun `a TestNG suite is captured through ServiceLoader alone`(@TempDir dir: File) {
        // TestNG loads every ITestNGListener named in META-INF/services on the test classpath, so
        // no -javaagent is needed. An undiscovered listener leaves the build green, hence the map check.
        project(
            dir,
            """
                testImplementation("org.testng:testng:7.10.2")
            """.trimIndent(),
            "useTestNG()",
            subject,
            "src/test/java/dev/engine/SubjectTest.java" to """
                package dev.engine;
                import org.testng.annotations.Test;
                import static org.testng.Assert.assertEquals;
                public class SubjectTest {
                    @Test public void doubles() { assertEquals(new Subject().twice(1), 2); }
                    @Test public void triples() { assertEquals(new Subject().twice(3), 6); }
                }
            """.trimIndent(),
        )

        val result = runner(dir, "test").build()

        assertContains(result.output, "map updated:")
        val map = dir.walkTopDown().first { it.name == "coverage.tsv" }.readText()
        assertContains(map, "[engine:testng]/[class:dev.engine.SubjectTest]/[method:doubles]")
        assertContains(map, "[engine:testng]/[class:dev.engine.SubjectTest]/[method:triples]")
    }

    @Test
    fun `TestNG on the platform beside Jupiter and Vintage records every engine's tests`(@TempDir dir: File) {
        // The TestNG engine discovers by a TestNG dry run, which calls TestNG's listeners as if each
        // test ran. Taken for a real start, that handed TestNG's own listener the JVM's capture, and
        // the Platform's listener, the one that sees every engine, recorded nothing.
        project(
            dir,
            """
                testImplementation(platform("org.junit:junit-bom:5.11.4"))
                testImplementation("org.junit.jupiter:junit-jupiter")
                testImplementation("junit:junit:4.13.2")
                testImplementation("org.testng:testng:7.10.2")
                testRuntimeOnly("org.junit.vintage:junit-vintage-engine")
                testRuntimeOnly("org.junit.support:testng-engine:1.1.0")
                testRuntimeOnly("org.junit.platform:junit-platform-launcher")
            """.trimIndent(),
            """useJUnitPlatform { includeEngines("junit-jupiter", "junit-vintage", "testng") }""",
            subject,
            "src/test/java/dev/engine/JupiterTest.java" to """
                package dev.engine;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertEquals;
                class JupiterTest {
                    @Test void doubles() { assertEquals(2, new Subject().twice(1)); }
                }
            """.trimIndent(),
            "src/test/java/dev/engine/VintageTest.java" to """
                package dev.engine;
                import org.junit.Test;
                import static org.junit.Assert.assertEquals;
                public class VintageTest {
                    @Test public void doubles() { assertEquals(2, new Subject().twice(1)); }
                }
            """.trimIndent(),
            "src/test/java/dev/engine/NgTest.java" to """
                package dev.engine;
                import org.testng.annotations.Test;
                import static org.testng.Assert.assertEquals;
                public class NgTest {
                    @Test public void doubles() { assertEquals(new Subject().twice(1), 2); }
                }
            """.trimIndent(),
        )

        runner(dir, "test").build()

        val map = dir.walkTopDown().first { it.name == "coverage.tsv" }.readText()
        listOf(
            "[engine:junit-jupiter]/[class:dev.engine.JupiterTest]/[method:doubles()]",
            "[engine:junit-vintage]/[runner:dev.engine.VintageTest]/[test:doubles(dev.engine.VintageTest)]",
            "[engine:testng]/[class:dev.engine.NgTest]/[method:doubles()]",
        ).forEach { id ->
            val record = map.lines().firstOrNull { it.endsWith("\t$id") }
            assertTrue(
                record != null && record.contains("dev.engine.Subject"),
                "no record covering the subject for $id: $map",
            )
        }
    }

    @Test
    fun `the vintage engine keeps its coverage when the agent is also attached`(@TempDir dir: File) {
        // The vintage engine drives RunNotifier, which the agent's hook also rewrites. If both
        // capture, each resets the other's window and a record comes back covering nothing. Such a
        // record is known to the map, so the unknown-test rule does not force it: a silent skip.
        project(
            dir,
            """
                testImplementation("junit:junit:4.13.2")
                testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.11.4")
                testRuntimeOnly("org.junit.platform:junit-platform-launcher")
            """.trimIndent(),
            "useJUnitPlatform()",
            subject,
            "src/test/java/dev/engine/SubjectTest.java" to """
                package dev.engine;
                import org.junit.Test;
                import static org.junit.Assert.assertEquals;
                public class SubjectTest {
                    @Test public void doubles() { assertEquals(2, new Subject().twice(1)); }
                    @Test public void triples() { assertEquals(6, new Subject().twice(3)); }
                }
            """.trimIndent(),
        )

        runner(dir, "test", "-Pyoriwake.internal.loaded").build()

        val map = dir.walkTopDown().first { it.name == "coverage.tsv" }.readText()
        val platformRecords = map.lines().filter { it.contains("[runner:dev.engine.SubjectTest]") }
        assertTrue(platformRecords.size >= 2, "expected a record per vintage test, got: $map")
        // Every Platform record must still name the classes it covered.
        platformRecords.forEach { record ->
            assertTrue(record.contains("dev.engine.Subject"), "a vintage record lost its coverage: $map")
        }
        // And the hook must not have run at all here; it is for runners with no Platform listener.
        assertTrue(
            !map.contains("[engine:junit4]"),
            "the JUnit 4 hook captured on a Platform task, which is what stole the coverage: $map",
        )
    }
}
