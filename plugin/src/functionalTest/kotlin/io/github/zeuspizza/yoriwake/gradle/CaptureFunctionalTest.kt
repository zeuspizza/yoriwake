package io.github.zeuspizza.yoriwake.gradle

import io.github.zeuspizza.yoriwake.agent.contract.AgentContract
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Capture: what a plain run records into the map, and what it instruments to do so. */
class CaptureFunctionalTest : FunctionalTestSupport() {

    @Test
    fun `applying the plugin does not break a build that runs tests`(@TempDir dir: File) {
        build(dir, "build.gradle.kts" to minimalBuild, oneClass, oneTest)

        val result = runner(dir, "test").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":test")?.outcome)
    }

    @Test
    fun `the resolved configuration is reported at execution time`(@TempDir dir: File) {
        // The only evidence a setting applied is the task reporting it back.
        build(dir, "build.gradle.kts" to minimalBuild, oneClass, oneTest)

        val output = runner(dir, "test").build().output

        assertContains(output, "[yoriwake] :test")
        // The diagnostic reports the outcome, not the intent: whether the scope was derived,
        // adopted from the host, or never applied at all.
        assertContains(output, "scope=derived(dev.sample.*)")
        assertContains(output, "includes=[dev.sample.*]")
    }

    @Test
    fun `the map directory is under gradle, not build, so clean does not destroy it`(@TempDir dir: File) {
        build(dir, "build.gradle.kts" to minimalBuild, oneClass, oneTest)

        val output = runner(dir, "test").build().output

        assertTrue(
            output.contains(File(".gradle").path + File.separator + "yoriwake"),
            "expected the map under .gradle/yoriwake, got: ${output.lines().firstOrNull { it.contains("[yoriwake]") }}",
        )
    }

    @Test
    fun `a map captured before a parallel run is byte-identical after it`(@TempDir dir: File) {
        // A green build only proves nothing threw. Tests interleaved in one JVM share a coverage
        // agent, so their records would be blended, and a blended map is believed by every later
        // run.
        build(dir, "build.gradle.kts" to minimalBuild, oneClass, oneTest)
        runner(dir, "test").build()
        val mapDir = File(dir, ".gradle/yoriwake").listFiles()!!.single(File::isDirectory)
        // The map's knowledge, which a blended capture would corrupt. Excluded on purpose:
        //  - `raw/` is staging the decode consumes; its rows are already in coverage.tsv.
        //  - `task-facts` gains `parallelism=<source>`, which is how the audit names the blocker.
        //  - `decisions.tsv` gains the refusal row, so the declined run is recorded as refused.
        // Add every new map file here: this is a filter, so an unlisted file escapes comparison.
        val knowledge = setOf("coverage.tsv", "scope", "effective-scope", "constants",
                              "class-digests",
                              "schema-version", "loaded", "loaded-scope", "capture-commit")
        fun mapFiles() = mapDir.walkTopDown().filter(File::isFile)
            .filter { it.name in knowledge && it.parentFile == mapDir }
            .associate { it.name to it.readBytes().toList() }
        val before = mapFiles()
        assertTrue(before.isNotEmpty(), "the serial run captured nothing, so this proves nothing")
        assertTrue(
            File(mapDir, "coverage.tsv").readText().isNotEmpty(),
            "the serial run left no coverage, so a byte-identical map proves nothing",
        )

        File(dir, "build.gradle.kts").writeText(
            minimalBuild + """

                tasks.test {
                    systemProperty("junit.jupiter.execution.parallel.enabled", "true")
                }
            """.trimIndent()
        )
        val result = runner(dir, "test", "--rerun-tasks").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":test")?.outcome)
        val after = mapFiles()
        assertEquals(before.keys, after.keys, "the declined run added or removed a map file")
        assertTrue(before.size >= 3, "too little of the map was compared: ${before.keys}")
        before.forEach { (path, bytes) ->
            assertEquals(bytes, after[path], "the declined run rewrote $path")
        }
        // And it recorded nothing: no index.tsv means the listener never wrote a row, which is the
        // half of the decline that stops a blended map rather than merely stopping a selection.
        assertTrue(
            mapDir.walkTopDown().none { it.name == "index.tsv" },
            "the declined run wrote records: ${mapDir.walkTopDown().filter(File::isFile).toList()}",
        )
    }

    @Test
    fun `parallel forks are not refused, and the recorded worker count is what really forked`(
        @TempDir dir: File,
    ) {
        // Two test classes, because two forks over one class run one JVM. The recorded worker count
        // is the divisor for recorded test time, so it must come from what really forked, not the
        // setting.
        build(
            dir,
            "build.gradle.kts" to minimalBuild + """

                tasks.test { maxParallelForks = 2 }
            """.trimIndent(),
            oneClass,
            oneTest,
            "src/test/java/dev/sample/BetaTest.java" to """
                package dev.sample;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertEquals;
                class BetaTest {
                    @Test void alsoPasses() { assertEquals(4, new Alpha().twice(2)); }
                }
            """.trimIndent(),
        )

        val result = runner(dir, "test").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":test")?.outcome)
        val mapDir = File(dir, ".gradle/yoriwake").listFiles()!!.single(File::isDirectory)
        val facts = File(mapDir, "task-facts").readText()
        val workers = facts.lines().firstOrNull { it.startsWith("workers=") }
            ?.substringAfter("workers=")?.trim()?.toIntOrNull()
        assertNotNull(workers, "no worker count was recorded at all: $facts")
        assertTrue(workers > 1, "a two-fork run recorded $workers worker(s): $facts")
    }

    @Test
    fun `jacoco includes are scoped to the project's own packages`(@TempDir dir: File) {
        build(
            dir,
            "build.gradle.kts" to minimalBuild + """

                tasks.register("printIncludes") {
                    val includes = provider {
                        (tasks.named("test").get().extensions.getByName("jacoco")
                            as org.gradle.testing.jacoco.plugins.JacocoTaskExtension).includes
                    }
                    doLast { println("INCLUDES=" + includes.get()) }
                }
            """.trimIndent(),
            oneClass,
            oneTest,
        )

        val output = runner(dir, "printIncludes").build().output

        assertContains(output, "INCLUDES=[dev.sample.*]")
    }

    /**
     * Prints the live `includeNoLocationClasses` off the test task's jacoco extension.
     *
     * Read back the way the plugin's own diagnostic reads it: the accessor is
     * `isIncludeNoLocationClasses()`, not a `get...` getter.
     */
    private val printNoLocation = """

        tasks.register("printNoLocation") {
            val flag = provider {
                (tasks.named("test").get().extensions.getByName("jacoco")
                    as org.gradle.testing.jacoco.plugins.JacocoTaskExtension)
                    .isIncludeNoLocationClasses
            }
            doLast { println("NOLOCATION=" + flag.get()) }
        }
    """.trimIndent()

    @Test
    fun `classes with no code-source location are instrumented`(@TempDir dir: File) {
        // Robolectric defines every class in its sandbox from bytes it read itself. Without this
        // JaCoCo skips them all, and an unattributable suite runs on every build.
        build(dir, "build.gradle.kts" to minimalBuild + printNoLocation, oneClass, oneTest)

        val output = runner(dir, "printNoLocation").build().output

        assertContains(output, "NOLOCATION=true")
    }

    @Test
    fun `classes with no location are instrumented even when the host scopes its own includes`(
        @TempDir dir: File,
    ) {
        // Applied before the early return that adopts a host's own jacoco includes, or exactly the
        // builds likely to have Robolectric would silently miss it.
        val hostScoped = minimalBuild + """

            tasks.test { extensions.configure<JacocoTaskExtension> { includes = listOf("dev.host.*") } }
        """.trimIndent() + printNoLocation
        build(dir, "build.gradle.kts" to hostScoped, oneClass, oneTest)

        val output = runner(dir, "printNoLocation").build().output

        assertContains(output, "NOLOCATION=true")
    }

    /** Prints the live `excludeClassLoaders` off the test task's jacoco extension. */
    private val printExcludedLoaders = """

        tasks.register("printExcludedLoaders") {
            val loaders = provider {
                (tasks.named("test").get().extensions.getByName("jacoco")
                    as org.gradle.testing.jacoco.plugins.JacocoTaskExtension).excludeClassLoaders
            }
            doLast { println("EXCLLOADERS=" + loaders.get()) }
        }
    """.trimIndent()

    @Test
    fun `the JDK's reflection classloaders are excluded from instrumentation`(@TempDir dir: File) {
        // Contains the setting above. JaCoCo's default names only the JDK 8 spelling, so on a
        // modern JDK the generated serialization accessors would be instrumented, which costs the
        // entire suite.
        build(dir, "build.gradle.kts" to minimalBuild + printExcludedLoaders, oneClass, oneTest)

        val output = runner(dir, "printExcludedLoaders").build().output

        assertContains(output, "sun.reflect.DelegatingClassLoader")
        assertContains(output, "jdk.internal.reflect.DelegatingClassLoader")
    }

    @Test
    fun `a host's own excluded classloaders are kept`(@TempDir dir: File) {
        // Replacing the list would silently drop a loader the host chose to exclude, which is the
        // same class of defect as overwriting their includes.
        val hostExcluded = minimalBuild + """

            tasks.test {
                extensions.configure<JacocoTaskExtension> {
                    excludeClassLoaders = listOf("dev.host.OwnLoader")
                }
            }
        """.trimIndent() + printExcludedLoaders
        build(dir, "build.gradle.kts" to hostExcluded, oneClass, oneTest)

        val output = runner(dir, "printExcludedLoaders").build().output

        assertContains(output, "dev.host.OwnLoader")
        assertContains(output, "jdk.internal.reflect.DelegatingClassLoader")
    }

    @Test
    fun `a real run produces a decoded map containing the classes its tests covered`(
        @TempDir dir: File,
    ) {
        // The end-to-end claim of capture: raw execution data is captured in the test JVM, decoded
        // on the daemon side, and lands as plain text the selector can read without
        // org.jacoco.core.
        build(dir, "build.gradle.kts" to minimalBuild, oneClass, oneTest)

        val output = runner(dir, "test").build().output

        val mapDir = File(dir, ".gradle/yoriwake").listFiles()?.singleOrNull(File::isDirectory)
        assertTrue(mapDir != null, "no map directory was created; output: $output")

        val coverage = File(mapDir, "coverage.tsv")
        assertTrue(coverage.isFile, "no coverage.tsv under $mapDir")
        assertContains(coverage.readText(), "dev.sample.Alpha")
        assertContains(coverage.readText(), "SUCCESSFUL")

        // Compared against the decoder's constant, not a literal, so a schema bump cannot drift
        // from this test.
        assertEquals(
            AgentContract.MAP_SCHEMA_VERSION.toString(),
            File(mapDir, "schema-version").readText().trim(),
        )
        assertContains(output, "map updated:")
    }

    private val failingTest = "src/test/java/dev/sample/GammaTest.java" to """
        package dev.sample;
        import org.junit.jupiter.api.Test;
        import static org.junit.jupiter.api.Assertions.assertEquals;
        class GammaTest {
            @Test void fails() { assertEquals(1, new Alpha().twice(1)); }
        }
    """.trimIndent()

    @Test
    fun `a run with a failing test still updates the map`(@TempDir dir: File) {
        // Gradle skips a task's own actions once it has failed, so decoding cannot live in doLast.
        // A red suite is the state you run tests to get out of; a tool that cannot learn during it
        // learns almost never.
        build(dir, "build.gradle.kts" to minimalBuild, oneClass, oneTest, failingTest)

        val result = runner(dir, "test").buildAndFail()

        val mapDir = File(dir, ".gradle/yoriwake").listFiles()?.singleOrNull(File::isDirectory)
        assertTrue(mapDir != null, "no map directory; output: ${result.output}")
        assertTrue(File(mapDir, "coverage.tsv").isFile, "the map was not written after a failure")
        assertContains(result.output, "map updated:")
    }

    @Test
    fun `the failing test is recorded as failed so it always runs again`(@TempDir dir: File) {
        build(dir, "build.gradle.kts" to minimalBuild, oneClass, oneTest, failingTest)

        runner(dir, "test").buildAndFail()

        val mapDir = File(dir, ".gradle/yoriwake").listFiles()!!.single(File::isDirectory)
        // Not just any line mentioning GammaTest: its class-scoped setup record mentions it too,
        // and that one is deliberately recorded as NONE because no single test owns it.
        val gamma = File(mapDir, "coverage.tsv").readLines()
            .single { it.contains("GammaTest") && !it.contains("[yoriwake:") }
        assertTrue(gamma.startsWith("FAILED"), "recorded as: ${gamma.substringBefore('	')}")
    }

    @Test
    fun `capturing with the recorder writes a union, and a class newer than it is judged on the rest`(
        @TempDir dir: File,
    ) {
        // A class added since capture is absent from the union by construction. A constant holder
        // is refused by the recordability check, and a class reached through a service file or a
        // resource by the resource scan.
        // What is left is a new class nothing references, which may be skipped: anything that used
        // it would itself be in the change set, with a referrer the map either covers or refuses to
        // vouch for.
        build(dir, "build.gradle.kts" to minimalBuild, oneClass, oneTest, secondClass, secondTest)
        ignoreBuildOutputs(dir)
        git(dir, "init")
        git(dir, "-c", "user.email=t@example.com", "-c", "user.name=t", "commit",
            "--allow-empty", "-m", "base")
        git(dir, "add", ".")
        git(dir, "-c", "user.email=t@example.com", "-c", "user.name=t", "commit", "-m", "sample")

        val captured = runner(dir, "test", "-Pyoriwake.internal.loaded").build().output
        assertContains(captured, "map updated:")

        val mapDir = File(dir, ".gradle/yoriwake").listFiles()!!.single(File::isDirectory)
        // Asserted because its absence is undetectable from outside: without a recorded scope
        // nothing is provable and every changed class keeps forcing.
        val recordedScope = File(mapDir, AgentContract.EFFECTIVE_SCOPE_FILE)
        assertTrue(recordedScope.isFile, "capture recorded no instrumentation scope; output: $captured")
        assertContains(recordedScope.readText(), "dev.sample")
        val union = File(mapDir, "loaded.txt")
        assertTrue(union.isFile, "no loaded union was written; output: $captured")
        assertContains(union.readText(), "dev.sample.Alpha")
        assertEquals("full", File(mapDir, "loaded-provenance").readText().trim())

        File(dir, "src/main/java/dev/sample/Unrelated.java").writeText(
            """
            package dev.sample;
            public class Unrelated { public int n() { return 1; } }
            """.trimIndent()
        )

        val narrowed = runner(dir, "test", "-Pyoriwake.select").build().output

        assertContains(narrowed, "skipped=2")

        // And the same class, once something reaches it, runs again. A test that only ever asserts
        // the narrowing would pass just as well if the rule had no conditions at all.
        File(dir, "src/test/java/dev/sample/UnrelatedTest.java").writeText(
            """
            package dev.sample;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            class UnrelatedTest {
                @Test void n() { assertEquals(1, new Unrelated().n()); }
            }
            """.trimIndent()
        )

        val withATest = runner(dir, "test", "-Pyoriwake.select").build().output

        assertContains(withATest, "not-in-map=1")
    }

    @Test
    fun `a filtered capture does not claim to speak for the whole task`(@TempDir dir: File) {
        // Gradle applies --tests during task-graph selection, after every afterEvaluate has run, so
        // a gate computed at configuration time cannot see it. A one-class run must not leave a
        // union stamped `full`.
        build(dir, "build.gradle.kts" to minimalBuild, oneClass, oneTest, secondClass, secondTest)

        runner(dir, "test", "-Pyoriwake.internal.loaded", "--tests", "dev.sample.AlphaTest").build()

        val mapDir = File(dir, ".gradle/yoriwake").listFiles()!!.single(File::isDirectory)
        assertTrue(!File(mapDir, "loaded.txt").exists(), "a filtered run must not write a union")
    }

    @Test
    fun `a Test task reconfigured late keeps the host's own jacoco scope`(@TempDir dir: File) {
        // Set in the host's own afterEvaluate, after the plugin was applied: the wiring must still
        // see it, and adopt it rather than overwrite it.
        build(
            dir,
            "build.gradle.kts" to (
                minimalBuild + System.lineSeparator() + """
                    afterEvaluate {
                        tasks.test { extensions.getByType<JacocoTaskExtension>().includes = listOf("dev.sample.*") }
                    }
                """.trimIndent()
                ),
            oneClass, oneTest,
        )
        ignoreBuildOutputs(dir)

        val output = runner(dir, "test").build().output

        assertContains(output, "scope=host(dev.sample.*)")
    }

    @Test
    fun `a narrowed run does not instrument and leaves the map untouched`(@TempDir dir: File) {
        // Capture only when the run is full anyway. A narrowed run is trying to be
        // fast, and the most it could produce is a partial map, so it captures nothing.
        //
        // The map's own bytes are the assertion. Anything weaker -- a log line, an absent jacoco
        // arg -- would still pass if the listener kept writing records with no coverage behind
        // them, which is the failure that matters here: every test recorded as covering nothing is
        // a silent skip in the map for every run afterwards.
        build(dir, "build.gradle.kts" to minimalBuild, oneClass, oneTest, secondClass, secondTest, classOrderByName)
        ignoreBuildOutputs(dir)
        git(dir, "init")
        git(dir, "-c", "user.email=t@example.com", "-c", "user.name=t", "commit",
            "--allow-empty", "-m", "base")
        git(dir, "add", ".")
        git(dir, "-c", "user.email=t@example.com", "-c", "user.name=t", "commit", "-m", "sample")

        runner(dir, "test").build()
        val mapDir = File(dir, ".gradle/yoriwake").listFiles()!!.single(File::isDirectory)
        val before = File(mapDir, "coverage.tsv").readText()

        File(dir, "src/main/java/dev/sample/Alpha.java").writeText(
            """
            package dev.sample;
            public class Alpha { public int twice(int n) { return n + n; } }
            """.trimIndent()
        )
        git(dir, "add", ".")
        git(dir, "-c", "user.email=t@example.com", "-c", "user.name=t", "commit", "-m", "touch alpha")
        File(dir, "build/test-results").deleteRecursively()

        val output = runner(dir, "test", "-Pyoriwake.select").build().output

        // BetaTest ran after Alpha was first loaded, so it runs too, uninstrumented like AlphaTest.
        assertEquals(setOf("dev.sample.AlphaTest", "dev.sample.BetaTest"), ranTests(dir))
        assertEquals("narrowed", decisionNotes(dir)["outcome"], "the run should have narrowed")
        assertContains(output, "narrowing, so nothing is instrumented")
        assertEquals(
            before,
            File(mapDir, "coverage.tsv").readText(),
            "a narrowed run rewrote the map. Recording without coverage behind it writes every " +
                "test as covering nothing, which is a silent skip for every later run.",
        )
    }

    @Test
    fun `a full run that the map already covers is not instrumented either`(@TempDir dir: File) {
        // A full run where nothing changed that the map does not already know has nothing to learn,
        // so it pays no toll.
        build(dir, "build.gradle.kts" to minimalBuild, oneClass, oneTest, secondClass, secondTest)
        ignoreBuildOutputs(dir)
        git(dir, "init")
        git(dir, "-c", "user.email=t@example.com", "-c", "user.name=t", "commit",
            "--allow-empty", "-m", "base")
        git(dir, "add", ".")
        git(dir, "-c", "user.email=t@example.com", "-c", "user.name=t", "commit", "-m", "sample")

        runner(dir, "test").build()
        File(dir, "build/test-results").deleteRecursively()

        // A path no source root claims and one the build reads, so it forces. A README would not:
        // the build never reads it, so that change is proven irrelevant and nothing runs.
        File(dir, "build.gradle.kts").appendText("\n// coverage cannot see a build script\n")
        git(dir, "add", ".")
        git(dir, "-c", "user.email=t@example.com", "-c", "user.name=t", "commit", "-m", "build tweak")

        // --rerun-tasks because nothing but a doc file changed, so :test is otherwise UP-TO-DATE
        // and never executes. That is Gradle behaving correctly and it hides the case under test.
        val output = runner(dir, "test", "-Pyoriwake.select", "--rerun-tasks").build().output

        assertEquals(
            setOf("dev.sample.AlphaTest", "dev.sample.BetaTest"), ranTests(dir),
            "an unmappable path must still force the whole suite",
        )
        assertContains(output, "the map already covers this commit")
    }

    /**
     * A file in the default package, which widens the derived scope to every class on the classpath.
     *
     * That is the precondition for the failure below: with a narrow scope, JaCoCo's includes
     * matcher already keeps the JDK's generated classes out.
     */
    private val defaultPackageClass = "src/main/java/Root.java" to """
        public class Root { public int one() { return 1; } }
    """.trimIndent()

    private val serializationRoundTrip =
        "src/test/java/dev/sample/SerializationTest.java" to """
        package dev.sample;
        import java.io.*;
        import org.junit.jupiter.api.Test;
        import static org.junit.jupiter.api.Assertions.assertEquals;
        class SerializationTest {
            static class Payload implements Serializable {
                final int n;
                Payload(int n) { this.n = n; }
            }
            @Test void roundTrips() throws Exception {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                new ObjectOutputStream(bytes).writeObject(new Payload(7));
                Object back = new ObjectInputStream(
                    new ByteArrayInputStream(bytes.toByteArray())).readObject();
                assertEquals(7, ((Payload) back).n);
            }
        }
    """.trimIndent()

    @Test
    fun `a suite that deserializes still runs when the derived scope is everything`(
        @TempDir dir: File,
    ) {
        // Deserializing makes the JDK generate a constructor accessor at runtime, in a classloader
        // that cannot see JaCoCo's agent. Instrumenting it turns every deserialization into
        // ClassNotFoundException and a green suite into zero test results.
        build(
            dir,
            "build.gradle.kts" to minimalBuild,
            oneClass,
            oneTest,
            defaultPackageClass,
            serializationRoundTrip,
        )

        val result = runner(dir, "test").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":test")?.outcome)
        assertContains(ranTests(dir), "dev.sample.SerializationTest")
    }
}
