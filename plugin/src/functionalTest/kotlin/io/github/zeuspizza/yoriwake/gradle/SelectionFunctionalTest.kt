package io.github.zeuspizza.yoriwake.gradle

import io.github.zeuspizza.yoriwake.agent.contract.AgentContract
import io.github.zeuspizza.yoriwake.gradle.capture.CoverageDecoder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Selection: which tests a change runs, end to end through a real test JVM. */
class SelectionFunctionalTest : FunctionalTestSupport() {

    @Test
    fun `a change selects only the tests that reach it`(@TempDir dir: File) {
        // The end-to-end claim of selection, and the one that cannot be unit tested: the filter is
        // found by ServiceLoader inside a foreign test JVM, reads properties the plugin set, and
        // actually removes tests from the run.
        build(dir, "build.gradle.kts" to minimalBuild, oneClass, oneTest, secondClass, secondTest, classOrderByName)
        ignoreBuildOutputs(dir)
        git(dir, "init")
        git(dir, "-c", "user.email=t@example.com", "-c", "user.name=t", "commit",
            "--allow-empty", "-m", "base")
        git(dir, "add", ".")
        git(dir, "-c", "user.email=t@example.com", "-c", "user.name=t", "commit", "-m", "sample")

        runner(dir, "test").build()
        assertEquals(setOf("dev.sample.AlphaTest", "dev.sample.BetaTest"), ranTests(dir))

        File(dir, "build/test-results").deleteRecursively()
        // Beta, not Alpha: AlphaTest runs first, so it ran before Beta was ever loaded and is
        // the test the change provably cannot reach. A change to Alpha would select both.
        changeBeta(dir)

        val output = runner(dir, "test", "-Pyoriwake.select").build().output

        // The base is the merge base with the parent branch, not HEAD, so an already committed
        // change is still visible.
        assertContains(output, "merge base with")
        assertEquals(setOf("dev.sample.BetaTest"), ranTests(dir))
    }

    @Test
    fun `class-granular selection is off unless explicitly asked for`(@TempDir dir: File) {
        // The JUnit 4 capture path mis-attributes coverage for tests that run other tests, so the
        // safe default runs everything.
        build(dir, "build.gradle.kts" to junit4Build, oneClass, betaClass, *junit4Tests)
        ignoreBuildOutputs(dir)
        git(dir, "init")
        git(dir, "-c", "user.email=t@example.com", "-c", "user.name=t", "commit",
            "--allow-empty", "-m", "base")
        git(dir, "add", ".")
        git(dir, "-c", "user.email=t@example.com", "-c", "user.name=t", "commit", "-m", "sample")

        runner(dir, "test").build()

        File(dir, "build/test-results").deleteRecursively()
        File(dir, "src/main/java/dev/sample/Alpha.java").writeText(
            """
            package dev.sample;
            public class Alpha { public int twice(int n) { return n + n; } }
            """.trimIndent()
        )

        runner(dir, "test", "-Pyoriwake.select").build()

        assertEquals(
            setOf("dev.sample.AlphaTest", "dev.sample.BetaTest"),
            ranTests(dir),
            "without -Pyoriwake.internal.classSelection a non-Platform build must run everything",
        )
    }

    @Test
    fun `a plain JUnit 4 build deselects at class granularity`(@TempDir dir: File) {
        // Deselection is a JUnit Platform PostDiscoveryFilter, which a JUnit 4 build never
        // consults. Gradle's own filter works for every framework, so selection here is
        // class-granular. One JVM per class, because JUnit 4's class order is the file system's,
        // and in a shared JVM a change selects every class that ran after its class was loaded.
        build(
            dir,
            "build.gradle.kts" to junit4Build.replace("tasks.test { useJUnit() }", "tasks.test { useJUnit(); forkEvery = 1 }"),
            oneClass, betaClass, *junit4Tests,
        )
        ignoreBuildOutputs(dir)
        git(dir, "init")
        git(dir, "-c", "user.email=t@example.com", "-c", "user.name=t", "commit",
            "--allow-empty", "-m", "base")
        git(dir, "add", ".")
        git(dir, "-c", "user.email=t@example.com", "-c", "user.name=t", "commit", "-m", "sample")

        runner(dir, "test").build()
        assertEquals(setOf("dev.sample.AlphaTest", "dev.sample.BetaTest"), ranTests(dir))

        File(dir, "build/test-results").deleteRecursively()
        File(dir, "src/main/java/dev/sample/Alpha.java").writeText(
            """
            package dev.sample;
            public class Alpha { public int twice(int n) { return n + n; } }
            """.trimIndent()
        )

        val output = runner(dir, "test", "-Pyoriwake.select", "-Pyoriwake.internal.classSelection")
            .build().output

        assertContains(output, "class granularity")
        assertEquals(setOf("dev.sample.AlphaTest"), ranTests(dir))
    }

    @Test
    fun `a JUnit 4 test class the map has never seen still runs`(@TempDir dir: File) {
        // The safety case for class-granular selection: the filter excludes what the map says runs
        // nothing and never includes the complement, so a test class added since capture survives.
        build(dir, "build.gradle.kts" to junit4Build, oneClass, betaClass, *junit4Tests)
        ignoreBuildOutputs(dir)
        git(dir, "init")
        git(dir, "-c", "user.email=t@example.com", "-c", "user.name=t", "commit",
            "--allow-empty", "-m", "base")
        git(dir, "add", ".")
        git(dir, "-c", "user.email=t@example.com", "-c", "user.name=t", "commit", "-m", "sample")

        runner(dir, "test").build()

        File(dir, "build/test-results").deleteRecursively()
        File(dir, "src/main/java/dev/sample/Alpha.java").writeText(
            """
            package dev.sample;
            public class Alpha { public int twice(int n) { return n + n; } }
            """.trimIndent()
        )
        // Added after the map was built, so nothing in it mentions this class.
        File(dir, "src/test/java/dev/sample/GammaTest.java").writeText(
            """
            package dev.sample;
            import org.junit.Test;
            import static org.junit.Assert.assertEquals;
            public class GammaTest {
                @Test public void passes() { assertEquals(2, new Alpha().twice(1)); }
            }
            """.trimIndent()
        )

        runner(dir, "test", "-Pyoriwake.select", "-Pyoriwake.internal.classSelection").build()

        assertTrue(
            "dev.sample.GammaTest" in ranTests(dir),
            "a test class absent from the map must never be excluded; ran ${ranTests(dir)}",
        )
    }

    @Test
    fun `selection is off unless the invocation asks for it`(@TempDir dir: File) {
        // A build with the plugin applied must keep running its whole suite by default. A tool that
        // can skip tests should skip nothing until told to.
        build(dir, "build.gradle.kts" to minimalBuild, oneClass, oneTest, secondClass, secondTest)

        runner(dir, "test").build()

        assertEquals(setOf("dev.sample.AlphaTest", "dev.sample.BetaTest"), ranTests(dir))
    }

    @Test
    fun `a dirty edit captured and then reverted still selects the tests that saw it`(
        @TempDir dir: File,
    ) {
        // The stamp is HEAD even on a dirty tree. Revert the edit and `git diff` from the stamp no
        // longer shows Alpha, while AlphaTest's records describe the edited Alpha. Beta's edit keeps
        // the change set non-empty, because an empty one runs everything and would hide the skip.
        build(dir, "build.gradle.kts" to minimalBuild, oneClass, oneTest, secondClass, secondTest)
        ignoreBuildOutputs(dir)
        git(dir, "init")
        commit(dir, "base")
        val alpha = File(dir, oneClass.first)
        alpha.writeText(oneClass.second.replace("n * 2", "n + n"))
        runner(dir, "test").build()
        alpha.writeText(oneClass.second)
        File(dir, secondClass.first).writeText(secondClass.second.replace("n * 3", "n + n + n"))
        File(dir, "build/test-results").deleteRecursively()

        val output = runner(dir, "test", "-Pyoriwake.select", "-Pyoriwake.base=HEAD").build().output

        assertEquals(setOf("dev.sample.AlphaTest", "dev.sample.BetaTest"), ranTests(dir), output)
    }

    @Test
    fun `an edited gitignored fixture runs the test that reads it, from a build below the repo root`(
        @TempDir dir: File,
    ) {
        // `--exclude-standard` hides the file from the untracked listing, so it has to enter the
        // change set another way. The build sits in `backend/` so the added path must arrive in the
        // diff's coordinates.
        val backend = File(dir, "backend").also { it.mkdirs() }
        build(
            backend, "build.gradle.kts" to minimalBuild, oneClass, oneTest, secondClass, secondTest,
            "src/test/java/dev/sample/FixtureTest.java" to """
                package dev.sample;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertNotNull;
                class FixtureTest {
                    @Test void reads() { assertNotNull(FixtureTest.class.getResource("/fixture.local")); }
                }
            """.trimIndent(),
        )
        File(backend, ".gitignore").writeText("build/\n.gradle/\n*.local\n")
        val fixture = File(backend, "src/test/resources/fixture.local")
        fixture.parentFile.mkdirs()
        fixture.writeText("one")
        git(dir, "init")
        commit(dir, "base")
        runner(backend, "test").build()
        fixture.writeText("two")
        File(backend, secondClass.first).writeText(secondClass.second.replace("n * 3", "n + n + n"))
        File(backend, "build/test-results").deleteRecursively()

        val output = runner(backend, "test", "-Pyoriwake.select", "-Pyoriwake.base=HEAD").build().output

        assertContains(ranTests(backend), "dev.sample.FixtureTest", output)
    }

    @Test
    fun `build output of another Gradle build below the root is not a change`(@TempDir dir: File) {
        // buildSrc, an included build, a TestKit project a test wrote: this build knows none of
        // their build directories, and a suite that runs builds leaves thousands of files there.
        // Paths with `src/` in them, so that on their own they would force a full run.
        build(
            dir, "build.gradle.kts" to minimalBuild, oneClass, oneTest, secondClass, secondTest,
            classOrderByName, "buildSrc/build.gradle.kts" to "",
        )
        committed(dir)
        runner(dir, "test").build()
        val work = File(dir, "buildSrc/build/tmp/test/work")
        repeat(20) { i ->
            File(work, "project$i/src/main/java/Generated$i.java").also { it.parentFile.mkdirs() }
                .writeText("class Generated$i {}")
            File(work, "project$i/settings.gradle").writeText("")
        }
        File(dir, "build/test-results").deleteRecursively()
        changeBeta(dir)

        val output = runner(dir, "test", "-Pyoriwake.select", "-Pyoriwake.base=HEAD").build().output

        assertEquals(setOf("dev.sample.BetaTest"), ranTests(dir), output)
    }

    @Test
    fun `a change set longer than a command line still starts the test JVM, and runs everything`(
        @TempDir dir: File,
    ) {
        // Past both limits a JVM's command line meets: 128 KiB for one argument on Linux, 1 MiB for
        // the whole of it on macOS. Resources, so every path forces a full run.
        build(dir, "build.gradle.kts" to minimalBuild, oneClass, oneTest, secondClass, secondTest)
        committed(dir)
        runner(dir, "test").build()
        val bulk = File(dir, "src/test/resources/bulk/" + List(3) { "d".repeat(80) + it }.joinToString("/"))
        bulk.mkdirs()
        repeat(5_000) { File(bulk, "f$it.txt").writeText("x") }
        File(dir, "build/test-results").deleteRecursively()

        val output = runner(dir, "test", "-Pyoriwake.select", "-Pyoriwake.base=HEAD").build().output

        assertEquals(setOf("dev.sample.AlphaTest", "dev.sample.BetaTest"), ranTests(dir), output)
        val notes = decisionNotes(dir)
        assertEquals("full-run", notes["outcome"], notes.toString().take(2_000))
        assertEquals("unmappable-paths", notes["full-run-kind"])
    }

    private val orphanClass = "src/main/java/dev/sample/Orphan.java" to """
        package dev.sample;
        public class Orphan { public int once() { return 1; } }
    """.trimIndent()

    /**
     * A class no test executes, nothing loads, and no resource names.
     *
     * The narrowing needs every condition at once, and the second half of this test removes one: a
     * resource naming the class as a string, as a plugin registry resolving a type from YAML does,
     * with no bytecode edge and no coverage record anywhere.
     */
    @Test
    fun `a class nothing executes, loads or names stops forcing a full run`(@TempDir dir: File) {
        build(dir, "build.gradle.kts" to minimalBuild, oneClass, oneTest, orphanClass)
        ignoreBuildOutputs(dir)
        git(dir, "init")
        git(dir, "-c", "user.email=t@example.com", "-c", "user.name=t", "commit",
            "--allow-empty", "-m", "base")
        git(dir, "add", ".")
        git(dir, "-c", "user.email=t@example.com", "-c", "user.name=t", "commit", "-m", "sample")

        // -Pyoriwake.internal.loaded, because the rule refuses without a loaded-class union:
        // coverage proves only that nothing executed the class, and a test can load one and assert
        // on its fields and annotations without executing a line of it.
        runner(dir, "test", "-Pyoriwake.internal.loaded").build()

        File(dir, "src/main/java/dev/sample/Orphan.java").writeText(
            """
            package dev.sample;
            public class Orphan { public int once() { return 2; } }
            """.trimIndent()
        )

        val narrowed = runner(dir, "test", "-Pyoriwake.select").build().output

        assertContains(narrowed, "[yoriwake] of 1 tests discovered: skipped=1")

        // Now name it in a resource the task reads, and commit that so it is not itself the change.
        File(dir, "src/main/resources/flows/example.yaml").apply { parentFile.mkdirs() }
            .writeText("tasks:" + System.lineSeparator() + "  - type: dev.sample.Orphan")
        git(dir, "add", ".")
        git(dir, "-c", "user.email=t@example.com", "-c", "user.name=t", "commit", "-m", "flow")
        runner(dir, "test", "-Pyoriwake.internal.loaded").build()
        File(dir, "src/main/java/dev/sample/Orphan.java").writeText(
            """
            package dev.sample;
            public class Orphan { public int once() { return 3; } }
            """.trimIndent()
        )

        val forced = runner(dir, "test", "-Pyoriwake.select").build().output

        assertContains(forced, "[yoriwake] of 1 tests discovered: full-run=1")
    }

    /**
     * The same never-executed, never-loaded class, once plain and once under a class-level
     * annotation. The annotation is all a framework needs to wire it (a Dagger module whose
     * `@Binds` are abstract, a scanned bean), so its absence from the union proves nothing.
     */
    @Test
    fun `a class-level annotation keeps a class nothing executes or loads forcing`(@TempDir dir: File) {
        val wiring = "src/main/java/dev/sample/Wiring.java" to """
            package dev.sample;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.RUNTIME)
            public @interface Wiring { }
        """.trimIndent()
        fun wired(value: Int) = """
            package dev.sample;
            @Wiring
            public class Wired { public int once() { return $value; } }
        """.trimIndent()
        build(dir, "build.gradle.kts" to minimalBuild, oneClass, oneTest, orphanClass, wiring,
            "src/main/java/dev/sample/Wired.java" to wired(1))
        ignoreBuildOutputs(dir)
        git(dir, "init")
        git(dir, "-c", "user.email=t@example.com", "-c", "user.name=t", "commit",
            "--allow-empty", "-m", "base")
        git(dir, "add", ".")
        git(dir, "-c", "user.email=t@example.com", "-c", "user.name=t", "commit", "-m", "sample")
        runner(dir, "test", "-Pyoriwake.internal.loaded").build()

        val orphan = File(dir, "src/main/java/dev/sample/Orphan.java")
        val captured = orphan.readText()
        orphan.writeText(captured.replace("return 1;", "return 2;"))

        assertContains(
            runner(dir, "test", "-Pyoriwake.select").build().output,
            "[yoriwake] of 1 tests discovered: skipped=1",
        )

        orphan.writeText(captured)
        File(dir, "src/main/java/dev/sample/Wired.java").writeText(wired(2))

        val forced = runner(dir, "test", "-Pyoriwake.select").build().output

        assertContains(forced, "[yoriwake] of 1 tests discovered: full-run=1")
    }

    /**
     * The bytes comparison names a consumer whose source did not change.
     *
     * `Reader` reads a `static final int` from `Limits`, so javac copies the value into `Reader`'s
     * own bytecode and `Reader` stops mentioning `Limits` at all. Change the constant and git
     * reports one file while the compiler recompiles two.
     *
     * The run still forces on `constant-changed`, which the comparison cannot overrule.
     */
    @Test
    fun `the digest rule names a recompiled consumer the change set never mentioned`(@TempDir dir: File) {
        val limits = "src/main/java/dev/sample/Limits.java" to """
            package dev.sample;
            public class Limits { public static final int LIMIT = 10; }
        """.trimIndent()
        val reader = "src/main/java/dev/sample/Reader.java" to """
            package dev.sample;
            public class Reader { public int limit() { return Limits.LIMIT; } }
        """.trimIndent()
        val readerTest = "src/test/java/dev/sample/ReaderTest.java" to """
            package dev.sample;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            class ReaderTest {
                @Test void readsTheLimit() { assertEquals(10, new Reader().limit()); }
            }
        """.trimIndent()
        build(dir, "build.gradle.kts" to minimalBuild, oneClass, oneTest, limits, reader, readerTest)
        ignoreBuildOutputs(dir)
        git(dir, "init")
        git(dir, "-c", "user.email=t@example.com", "-c", "user.name=t", "commit",
            "--allow-empty", "-m", "base")
        git(dir, "add", ".")
        git(dir, "-c", "user.email=t@example.com", "-c", "user.name=t", "commit", "-m", "sample")

        runner(dir, "test", "-Pyoriwake.internal.loaded").build()
        val mapDir = File(dir, ".gradle/yoriwake").listFiles()!!.single(File::isDirectory)
        assertTrue(
            File(mapDir, CoverageDecoder.CLASS_DIGESTS_FILE).isFile,
            "the capture recorded no digest table, so the rule has nothing to compare against",
        )

        // One file changes: `Reader.java` is untouched and `Reader.class` is not.
        File(dir, "src/main/java/dev/sample/Limits.java").writeText(
            """
            package dev.sample;
            public class Limits { public static final int LIMIT = 11; }
            """.trimIndent()
        )

        runner(dir, "testClasses").build()

        runner(dir, "yoriwakeExplainTest").build()
        val explained = File(mapDir, "explain.json").readText()

        assertContains(explained, """"digestOn": true""")
        assertContains(explained, "dev.sample.Reader")
        assertContains(explained, """"refusalKind": "constant-changed"""")

        // And the entry stores and reuses, which is where a configuration-cache defect surfaces.
        val again = runner(dir, "yoriwakeExplainTest").build().output
        assertContains(again, "Configuration cache entry reused")
    }

    /**
     * `yoriwakeExplain` compiles what the test task compiles before it reads a class file. Asked
     * before anything recompiled a changed constant holder, it would digest the old bytes, see no
     * constant move, and predict a narrowed run the real one never makes.
     */
    @Test
    fun `yoriwakeExplain before a compile answers what the selecting run then does`(@TempDir dir: File) {
        val limits = "src/main/java/dev/sample/Limits.java" to """
            package dev.sample;
            public class Limits {
                public static final int LIMIT = 10;
                public static int twice() { return 2 * LIMIT; }
            }
        """.trimIndent()
        val reader = "src/main/java/dev/sample/Reader.java" to """
            package dev.sample;
            public class Reader { public int limit() { return Limits.LIMIT; } }
        """.trimIndent()
        val readerTest = "src/test/java/dev/sample/ReaderTest.java" to """
            package dev.sample;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            class ReaderTest {
                @Test void readsTheLimit() { assertEquals(10, new Reader().limit()); }
            }
        """.trimIndent()
        // Executes Limits, so the map knows it and a change to it can narrow.
        val limitsTest = "src/test/java/dev/sample/LimitsTest.java" to """
            package dev.sample;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertTrue;
            class LimitsTest {
                @Test void doubles() { assertTrue(Limits.twice() > 0); }
            }
        """.trimIndent()
        build(dir, "build.gradle.kts" to minimalBuild, oneClass, oneTest, limits, reader, readerTest, limitsTest)
        committed(dir)
        runner(dir, "test", "-Pyoriwake.internal.loaded").build()
        File(dir, "src/main/java/dev/sample/Limits.java").writeText(
            """
            package dev.sample;
            public class Limits {
                public static final int LIMIT = 11;
                public static int twice() { return 2 * LIMIT; }
            }
            """.trimIndent()
        )

        // No compile step first: the edit is still only in the source.
        val explained = runner(dir, "yoriwakeExplainTest").build().output
        val selected = runner(dir, "test", "-Pyoriwake.select").buildAndFail().output

        assertEquals("full-run", decisionNotes(dir)["outcome"], selected)
        assertContains(explained, ":test would run everything")
        val mapDir = File(dir, ".gradle/yoriwake").listFiles()!!.single(File::isDirectory)
        assertContains(File(mapDir, "explain.json").readText(), """"refusalKind": "constant-changed"""")
    }

    /**
     * Both execution-time call sites run the bytes comparison, or they drift apart.
     *
     * A rule that lives at one call site answers the narrower question at the other, and a class
     * rewritten after compilation could then be deselected there.
     *
     * A JUnit 4 build reaches both -- the property-setting `doFirst` and the class-granular one --
     * where a Platform build returns from the second before it starts. Each site reports its
     * comparison, so a site that stopped running it prints one line instead of two.
     */
    @Test
    fun `the digest rule runs at both execution-time call sites`(@TempDir dir: File) {
        build(dir, "build.gradle.kts" to junit4Build, oneClass, betaClass, *junit4Tests)
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

        val output = runner(dir, "test", "-Pyoriwake.select", "-Pyoriwake.internal.classSelection")
            .build().output

        assertEquals(
            2,
            output.lines().count {
                it.contains("[yoriwake]") && it.contains(" compiled classes against ")
            },
            "the comparison ran at one execution-time site and not the other: $output",
        )
    }

    /** BetaTest, pinned in its own source with a tag that needs nothing on the classpath. */
    private val taggedSecondTest = "src/test/java/dev/sample/BetaTest.java" to """
        package dev.sample;
        import org.junit.jupiter.api.Tag;
        import org.junit.jupiter.api.Test;
        import static org.junit.jupiter.api.Assertions.assertEquals;
        @Tag("yoriwake-always-run")
        class BetaTest {
            @Test void passes() { assertEquals(3, new Beta().thrice(1)); }
        }
    """.trimIndent()

    /**
     * Sets up the checkout, captures a map, then changes Alpha. BetaTest cannot reach that change.
     *
     * The sibling test `a change selects only the tests that reach it` runs this exact scenario
     * without a pin and asserts BetaTest is excluded. The two differ only by the pin, so a pin that
     * did nothing would make them agree.
     */
    private fun changeAlphaAfterCapture(dir: File) {
        ignoreBuildOutputs(dir)
        git(dir, "init")
        git(dir, "-c", "user.email=t@example.com", "-c", "user.name=t", "commit",
            "--allow-empty", "-m", "base")
        git(dir, "add", ".")
        git(dir, "-c", "user.email=t@example.com", "-c", "user.name=t", "commit", "-m", "sample")
        runner(dir, "test").build()
        File(dir, "build/test-results").deleteRecursively()
        File(dir, "src/main/java/dev/sample/Alpha.java").writeText(
            """
            package dev.sample;
            public class Alpha { public int twice(int n) { return n + n; } }
            """.trimIndent()
        )
    }

    /**
     * A tag in the test's own source pins it, and nothing was added to the compile classpath.
     *
     * `@Tag` is standard JUnit Platform, so pinning needs no dependency and no edit beyond the tag.
     */
    @Test
    fun `a tagged test is run even though the change cannot reach it`(@TempDir dir: File) {
        build(dir, "build.gradle.kts" to minimalBuild, oneClass, oneTest, secondClass, taggedSecondTest)
        changeAlphaAfterCapture(dir)

        val output = runner(dir, "test", "-Pyoriwake.select").build().output

        assertEquals(setOf("dev.sample.AlphaTest", "dev.sample.BetaTest"), ranTests(dir))
        assertContains(output, "always-run by configuration")

        val mapDir = File(dir, ".gradle/yoriwake").listFiles()!!.single(File::isDirectory)
        val rows = File(mapDir, AgentContract.DECISIONS_FILE).readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
        val beta = rows.single { it.contains("BetaTest") }.split("\t")
        assertEquals("included", beta[1], beta.toString())
        assertEquals("ALWAYS_RUN", beta[2], "the pin must be distinguishable from a selection hit")
    }

    /**
     * A build-script pattern pins it, with no edit to any test source at all.
     */
    @Test
    fun `a pattern in the build script pins a test the change cannot reach`(@TempDir dir: File) {
        build(
            dir,
            "build.gradle.kts" to (
                minimalBuild + System.lineSeparator() +
                    """yoriwake { alwaysRun.add("dev.sample.BetaTest") }"""
                ),
            oneClass, oneTest, secondClass, secondTest,
        )
        changeAlphaAfterCapture(dir)

        runner(dir, "test", "-Pyoriwake.select").build()

        assertEquals(setOf("dev.sample.AlphaTest", "dev.sample.BetaTest"), ranTests(dir))
    }

    /**
     * A pattern that matches nothing is called out, because it is otherwise invisible.
     *
     * The build is green, the suite narrows exactly as it would have, and the user believes a flaky
     * test is being protected. That is indistinguishable from a suite where nothing needed pinning,
     * which is why the agent reports unmatched patterns rather than only counting hits.
     */
    @Test
    fun `a pattern that pins nothing says so`(@TempDir dir: File) {
        build(dir, "build.gradle.kts" to minimalBuild, oneClass, oneTest, secondClass, secondTest, classOrderByName)
        changeAlphaAfterCapture(dir)

        val output = runner(dir, "test", "-Pyoriwake.select", "-Pyoriwake.alwaysRun=dev.sample.Typoo*")
            .build().output

        assertContains(output, "matched NO test")
        assertContains(output, "dev.sample.Typoo*")
        // And it changed nothing: the selection is exactly what it would have been. BetaTest runs
        // because it ran after Alpha was first loaded, and the run still narrowed.
        assertEquals(setOf("dev.sample.AlphaTest", "dev.sample.BetaTest"), ranTests(dir))
        assertEquals("narrowed", decisionNotes(dir)["outcome"])
    }

    /**
     * A pattern that would pin the whole suite is refused at configuration time, not honoured.
     *
     * `*` is selection switched off wearing a configuration's clothes, and it would go on reporting
     * that it narrowed on every run.
     */
    @Test
    fun `a pattern that would pin everything fails the build and names itself`(@TempDir dir: File) {
        build(dir, "build.gradle.kts" to minimalBuild, oneClass, oneTest)
        ignoreBuildOutputs(dir)

        val failure = runner(dir, "test", "-Pyoriwake.alwaysRun=*").buildAndFail().output

        assertContains(failure, "pin every test")
        assertContains(failure, "yoriwake.disabled")
    }

    @Test
    fun `a branch with no upstream selects against main and does not claim a full run`(
        @TempDir dir: File,
    ) {
        build(dir, "build.gradle.kts" to minimalBuild, oneClass, secondClass, oneTest, secondTest, classOrderByName)
        ignoreBuildOutputs(dir)
        git(dir, "-c", "init.defaultBranch=main", "init")
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

        File(dir, "build/test-results").deleteRecursively()
        val output = runner(dir, "test", "-Pyoriwake.select").build().output

        // BetaTest ran after Alpha was first loaded in their JVM.
        assertEquals(setOf("dev.sample.AlphaTest", "dev.sample.BetaTest"), ranTests(dir))
        assertEquals("narrowed", decisionNotes(dir)["outcome"])
        assertFalse(output.contains("runs in full"), "a narrowed run claimed to run in full")
        assertContains(output, Regex("""selecting against [0-9a-f]{40} \(merge base with main"""))
    }

    @Test
    fun `a coverage edge created after the capture is still caught`(@TempDir dir: File) {
        // The map records BetaTest -> Beta. BetaTest is then edited to call Alpha, an edge the map
        // never saw, and Alpha breaks. Selecting on "what changed since the last commit" would skip
        // BetaTest.
        // The edit that created the edge is itself a change since the capture, so widening the base
        // to the map's age puts BetaTest's file in the change set. Capturing only on full runs
        // rests on this.
        // The run must still narrow: an empty change set forces a full run, which would also run
        // BetaTest and let this pass whether the widening works or not.
        val thirdClass = "src/main/java/dev/sample/Gamma.java" to """
            package dev.sample;
            public class Gamma { public int quad(int n) { return n * 4; } }
        """.trimIndent()
        val thirdTest = "src/test/java/dev/sample/GammaTest.java" to """
            package dev.sample;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            class GammaTest {
                @Test void passes() { assertEquals(4, new Gamma().quad(1)); }
            }
        """.trimIndent()
        build(dir, "build.gradle.kts" to minimalBuild, oneClass, oneTest, secondClass, secondTest,
            thirdClass, thirdTest)
        ignoreBuildOutputs(dir)
        git(dir, "init")
        git(dir, "-c", "user.email=t@example.com", "-c", "user.name=t", "commit",
            "--allow-empty", "-m", "base")
        git(dir, "add", ".")
        git(dir, "-c", "user.email=t@example.com", "-c", "user.name=t", "commit", "-m", "sample")

        runner(dir, "test").build()
        val mapDir = File(dir, ".gradle/yoriwake").listFiles()!!.single(File::isDirectory)
        val capturedAt = File(mapDir, CoverageDecoder.CAPTURE_COMMIT_FILE)
        val a = capturedAt.readText().trim()

        // BetaTest gains a call to Alpha. The map, captured before this, records no such edge.
        File(dir, "src/test/java/dev/sample/BetaTest.java").writeText(
            """
            package dev.sample;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            class BetaTest {
                @Test void passes() { assertEquals(3, new Beta().thrice(1)); }
                @Test void alsoUsesAlpha() { assertEquals(2, new Alpha().twice(1)); }
            }
            """.trimIndent()
        )
        git(dir, "add", ".")
        git(dir, "-c", "user.email=t@example.com", "-c", "user.name=t", "commit", "-m", "new edge")

        // Now Alpha changes, in a way that breaks the caller the map does not know about.
        File(dir, "src/main/java/dev/sample/Alpha.java").writeText(
            """
            package dev.sample;
            public class Alpha { public int twice(int n) { return n * 3; } }
            """.trimIndent()
        )
        git(dir, "add", ".")
        git(dir, "-c", "user.email=t@example.com", "-c", "user.name=t", "commit", "-m", "break alpha")

        // The map is what a cache hands back: built before either commit.
        capturedAt.writeText(a + "\n")
        File(dir, "build/test-results").deleteRecursively()

        // And `yoriwakeExplain` predicts the run it stands in for: it diffs from the same widened
        // base, not the raw one.
        runner(dir, "yoriwakeExplainTest").build()
        assertContains(File(mapDir, YoriwakePlugin.EXPLANATION_FILE).readText(), "\"base\": \"$a\"")

        runner(dir, "test", "-Pyoriwake.select").buildAndFail()
        val ran = ranTests(dir)

        assertTrue(
            "dev.sample.BetaTest" in ran,
            "BetaTest gained a call to Alpha after the capture and Alpha then broke, so it must " +
                "run. Selecting only what the stale map knows skips it and the failure is lost. " +
                "Ran: $ran",
        )
        // BetaTest's edit changed a test class every test's JVM loaded at discovery, so every
        // test runs; what proves the widening is the change set it was decided on.
        val notes = decisionNotes(dir)
        assertEquals("narrowed", notes["outcome"], "a full run would let BetaTest pass unwidened")
        assertTrue(
            "dev.sample.BetaTest" in notes["input.${AgentContract.CHANGED_CLASSES_PROPERTY}"].orEmpty().split(","),
            "the widened base must put BetaTest's edit in the change set: $notes",
        )
    }

    @Test
    fun `a map older than the base widens the base to cover its age`(@TempDir dir: File) {
        // A map that outlives the run that built it, as caching one in CI does. Captured at A and
        // selected against B, anything in between is invisible, including a commit that made a test
        // reach a class for the first time.
        // Widening to the common ancestor puts everything since A back in the change set. The stamp
        // is rewritten by hand because a local run always recaptures and cannot produce a stale
        // map.
        build(dir, "build.gradle.kts" to minimalBuild, oneClass, oneTest, secondClass, secondTest, classOrderByName)
        ignoreBuildOutputs(dir)
        git(dir, "init")
        git(dir, "-c", "user.email=t@example.com", "-c", "user.name=t", "commit",
            "--allow-empty", "-m", "base")
        git(dir, "add", ".")
        git(dir, "-c", "user.email=t@example.com", "-c", "user.name=t", "commit", "-m", "sample")

        runner(dir, "test").build()
        val mapDir = File(dir, ".gradle/yoriwake").listFiles()!!.single(File::isDirectory)
        val capturedAt = File(mapDir, CoverageDecoder.CAPTURE_COMMIT_FILE)
        assertTrue(capturedAt.isFile, "the map was not stamped with the commit it was captured at")
        val a = capturedAt.readText().trim()

        File(dir, "src/main/java/dev/sample/Alpha.java")
            .writeText(
                """
                package dev.sample;
                public class Alpha { public int twice(int n) { return n + n; } }
                """.trimIndent()
            )
        git(dir, "add", ".")
        git(dir, "-c", "user.email=t@example.com", "-c", "user.name=t", "commit", "-m", "move on")
        // The map is now what a CI cache would hand back: built at A, restored onto B.
        capturedAt.writeText(a + "\n")
        File(dir, "build/test-results").deleteRecursively()

        val output = runner(dir, "test", "-Pyoriwake.select").build().output

        assertContains(output, "widened to cover a map captured at")
        // Alpha changed in the commit the base was widened past, so its test is selected, and
        // BetaTest, which ran after Alpha was first loaded. Without the widening the change set
        // is empty and the run does not narrow.
        assertEquals(setOf("dev.sample.AlphaTest", "dev.sample.BetaTest"), ranTests(dir))
        assertEquals("narrowed", decisionNotes(dir)["outcome"])
    }

    /** Reads a resource and touches no class; sorts before BetaTest, so it shares no JVM with Beta. */
    private val assetTest = "src/test/java/dev/sample/AssetTest.java" to """
        package dev.sample;
        import org.junit.jupiter.api.Test;
        import static org.junit.jupiter.api.Assertions.assertEquals;
        class AssetTest {
            @Test void reads() throws Exception {
                try (var in = AssetTest.class.getResourceAsStream("/asset.txt")) {
                    assertEquals("good", new String(in.readAllBytes()).trim());
                }
            }
        }
    """.trimIndent()

    @Test
    fun `a map captured on another branch counts what differs from its capture commit as changed`(
        @TempDir dir: File,
    ) {
        // Captured on a branch whose commit fixed a resource; the user then works on a branch forked
        // before that commit. The widened base is the fork point, whose resource matches the tree, so
        // `git diff` from it misses the resource the map's records were made with.
        build(dir, "build.gradle.kts" to minimalBuild, oneClass, oneTest, secondClass, secondTest,
            assetTest, classOrderByName, "src/test/resources/asset.txt" to "bad")
        ignoreBuildOutputs(dir)
        git(dir, "init")
        git(dir, "-c", "user.email=t@example.com", "-c", "user.name=t", "commit",
            "--allow-empty", "-m", "base")
        git(dir, "add", ".")
        git(dir, "-c", "user.email=t@example.com", "-c", "user.name=t", "commit", "-m", "sample")
        git(dir, "checkout", "-b", "x")
        File(dir, "src/test/resources/asset.txt").writeText("good")
        git(dir, "-c", "user.email=t@example.com", "-c", "user.name=t", "commit", "-am", "fix asset")
        runner(dir, "test").build()

        git(dir, "checkout", "-b", "y", "HEAD~1")
        changeBeta(dir)
        git(dir, "-c", "user.email=t@example.com", "-c", "user.name=t", "commit", "-am", "beta")
        File(dir, "build/test-results").deleteRecursively()

        val output = runner(dir, "test", "-Pyoriwake.select").run().output

        assertTrue(
            "dev.sample.AssetTest" in ranTests(dir),
            "asset.txt differs from the tree the map was captured on, so AssetTest must run and " +
                "fail. Ran: ${ranTests(dir)}",
        )
        assertContains(output, "1 paths coverage cannot see")
        assertEquals("unmappable-paths", decisionNotes(dir)["full-run-kind"])
    }

    @Test
    fun `a map captured on this branch's history narrows as before`(@TempDir dir: File) {
        build(dir, "build.gradle.kts" to minimalBuild, oneClass, oneTest, secondClass, secondTest,
            assetTest, classOrderByName, "src/test/resources/asset.txt" to "good")
        ignoreBuildOutputs(dir)
        git(dir, "init")
        git(dir, "-c", "user.email=t@example.com", "-c", "user.name=t", "commit",
            "--allow-empty", "-m", "base")
        git(dir, "add", ".")
        git(dir, "-c", "user.email=t@example.com", "-c", "user.name=t", "commit", "-m", "sample")
        git(dir, "checkout", "-b", "x")
        runner(dir, "test").build()

        changeBeta(dir)
        git(dir, "-c", "user.email=t@example.com", "-c", "user.name=t", "commit", "-am", "beta")
        File(dir, "build/test-results").deleteRecursively()

        val output = runner(dir, "test", "-Pyoriwake.select").build().output

        assertContains(output, "1 changed classes, 0 paths coverage cannot see")
        assertEquals(setOf("dev.sample.BetaTest"), ranTests(dir))
        assertEquals("narrowed", decisionNotes(dir)["outcome"])
    }

    /**
     * A Kotlin build whose test inlines the code under test, as a whole build rather than a fixture.
     *
     * `aboveZero` is inline, so its body is compiled into InlinedTest's own bytecode and coverage
     * records no edge to Inlined.kt at all. Select on that change without reading the
     * SourceDebugExtension and the one test that would fail is the one that does not run.
     */
    @Test
    fun `a change to an inline function selects the test that inlined it`(@TempDir dir: File) {
        build(dir, "build.gradle.kts" to kotlinBuild, kotlinInlined, kotlinInlinedTest,
            kotlinBelowTenTest, kotlinAlpha, kotlinAlphaTest)
        ignoreBuildOutputs(dir)
        git(dir, "init")
        git(dir, "-c", "user.email=t@example.com", "-c", "user.name=t", "commit",
            "--allow-empty", "-m", "base")
        git(dir, "add", ".")
        git(dir, "-c", "user.email=t@example.com", "-c", "user.name=t", "commit", "-m", "sample")

        runner(dir, "test").build()
        assertEquals(
            setOf("dev.demokt.InlinedTest", "dev.demokt.BelowTenTest", "dev.demokt.AlphaTest"),
            ranTests(dir),
        )

        File(dir, "build/test-results").deleteRecursively()
        File(dir, "src/main/kotlin/dev/demokt/Inlined.kt").writeText(
            """
            package dev.demokt
            inline fun aboveZero(n: Int): Boolean = n >= 1
            fun belowTen(n: Int): Boolean = n < 10
            """.trimIndent()
        )

        val output = runner(dir, "test", "-Pyoriwake.select").build().output

        assertContains(output, "inlined the changed sources")
        assertTrue(
            "dev.demokt.InlinedTest" in ranTests(dir),
            "the inline body lives in InlinedTest's bytecode, so a change to Inlined.kt must " +
                "select it; ran ${ranTests(dir)}",
        )
    }

    /**
     * A scan that could not finish switches selection off, rather than narrowing on silence.
     *
     * `classesInlining` answering null must reach the test JVM as `yoriwake.select=false` and run
     * everything, or the refusal is computed correctly and thrown away.
     *
     * The scan is made to fail the way a real one does: a file in the build's own output that ends
     * in `.class` and is not a class file. It sits in the main output rather than the test output,
     * so the thing under test is the plugin's reaction to an unreadable class and not JUnit's.
     */
    @Test
    fun `a scan that cannot finish switches selection off and runs everything`(@TempDir dir: File) {
        build(
            dir,
            "build.gradle.kts" to minimalBuild + """

                // An extra output directory on the test runtime classpath, inside the build dir, so
                // the inline scan treats it as this build's own output and reads what is in it.
                sourceSets["test"].runtimeClasspath += files(layout.buildDirectory.dir("extra"))
            """.trimIndent(),
            oneClass, oneTest, secondClass, secondTest,
        )
        ignoreBuildOutputs(dir)
        git(dir, "init")
        git(dir, "-c", "user.email=t@example.com", "-c", "user.name=t", "commit",
            "--allow-empty", "-m", "base")
        git(dir, "add", ".")
        git(dir, "-c", "user.email=t@example.com", "-c", "user.name=t", "commit", "-m", "sample")

        File(dir, "build/extra/dev/sample").mkdirs()
        // Ends in `.class` and is not a class file, which is what a half-written or foreign entry
        // in an output directory looks like. The scan must not read past it and call itself done.
        File(dir, "build/extra/dev/sample/Broken.class").writeBytes(byteArrayOf(1, 2, 3))

        runner(dir, "test").build()
        File(dir, "build/test-results").deleteRecursively()
        File(dir, "src/main/java/dev/sample/Alpha.java").writeText(
            """
            package dev.sample;
            public class Alpha { public int twice(int n) { return n + n; } }
            """.trimIndent()
        )

        val output = runner(dir, "test", "-Pyoriwake.select").build().output

        assertContains(output, "could not be read, so the whole suite runs")
        // The refusal is worth nothing unless it reaches the JVM. Without the wiring, Alpha's
        // change selects AlphaTest alone and BetaTest is skipped on an answer nothing established.
        assertEquals(setOf("dev.sample.AlphaTest", "dev.sample.BetaTest"), ranTests(dir))
    }

    private val kotlinBuild = """
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
        tasks.test { useJUnitPlatform() }
    """.trimIndent()

    // `belowTen` is deliberately not inline: it gives the map real coverage records for InlinedKt,
    // from BelowTenTest, so a change to Inlined.kt can narrow. Without it the map has never seen
    // InlinedKt, the selector forces a full run, and this test would pass for the wrong reason.
    private val kotlinInlined = "src/main/kotlin/dev/demokt/Inlined.kt" to """
        package dev.demokt
        inline fun aboveZero(n: Int): Boolean = n > 0
        fun belowTen(n: Int): Boolean = n < 10
    """.trimIndent()

    private val kotlinInlinedTest = "src/test/kotlin/dev/demokt/InlinedTest.kt" to """
        package dev.demokt
        import org.junit.jupiter.api.Test
        import kotlin.test.assertTrue
        class InlinedTest {
            @Test fun positive() { assertTrue(aboveZero(1)) }
        }
    """.trimIndent()

    private val kotlinBelowTenTest = "src/test/kotlin/dev/demokt/BelowTenTest.kt" to """
        package dev.demokt
        import org.junit.jupiter.api.Test
        import kotlin.test.assertTrue
        class BelowTenTest {
            @Test fun below() { assertTrue(belowTen(9)) }
        }
    """.trimIndent()

    private val kotlinAlpha = "src/main/kotlin/dev/demokt/Alpha.kt" to """
        package dev.demokt
        class Alpha { fun twice(n: Int): Int = n * 2 }
    """.trimIndent()

    private val kotlinAlphaTest = "src/test/kotlin/dev/demokt/AlphaTest.kt" to """
        package dev.demokt
        import org.junit.jupiter.api.Test
        import kotlin.test.assertEquals
        class AlphaTest {
            @Test fun twice() { assertEquals(2, Alpha().twice(1)) }
        }
    """.trimIndent()

    @Test
    fun `a change to a class that declares a compile-time constant runs the whole suite`(
        @TempDir dir: File,
    ) {
        // javac copies a `static final` literal into every consumer, so a test that baked in the
        // old value names the holder nowhere in its bytecode and coverage records no edge to it.
        // BetaTest is that consumer. AlphaTest reaches Limits at runtime, so the map does record an
        // edge, which is what makes the narrowing possible and the miss observable.
        val limits = "src/main/java/dev/sample/Limits.java" to """
            package dev.sample;
            public class Limits {
                public static final int MAX = 10;
                public int max() { return MAX; }
            }
        """.trimIndent()
        val readsAtRuntime = "src/test/java/dev/sample/AlphaTest.java" to """
            package dev.sample;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            class AlphaTest {
                @Test void passes() { assertEquals(10, new Limits().max()); }
            }
        """.trimIndent()
        val bakedIn = "src/test/java/dev/sample/BetaTest.java" to """
            package dev.sample;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            class BetaTest {
                @Test void passes() { assertEquals(10, Limits.MAX); }
            }
        """.trimIndent()
        build(dir, "build.gradle.kts" to minimalBuild, limits, readsAtRuntime, bakedIn)
        ignoreBuildOutputs(dir)
        git(dir, "init")
        git(dir, "-c", "user.email=t@example.com", "-c", "user.name=t", "commit",
            "--allow-empty", "-m", "base")
        commit(dir, "sample")

        runner(dir, "test").build()
        File(dir, "build/test-results").deleteRecursively()

        File(dir, "src/main/java/dev/sample/Limits.java").writeText(
            """
            package dev.sample;
            public class Limits {
                public static final int MAX = 11;
                public int max() { return MAX; }
            }
            """.trimIndent()
        )
        commit(dir, "the constant moves")

        val output = runner(dir, "test", "-Pyoriwake.select").buildAndFail().output

        assertContains(output, "declares a compile-time constant")
        assertEquals(
            setOf("dev.sample.AlphaTest", "dev.sample.BetaTest"),
            ranTests(dir),
            "BetaTest baked the old value into its own bytecode and names Limits nowhere, so no " +
                "coverage edge exists to select it by. It must run anyway.",
        )
    }

    @Test
    fun `a constant another production class copied selects the tests that executed that class`(
        @TempDir dir: File,
    ) {
        // Gate copies Limits.MAX into a switch label and an annotation value, and GateTest never
        // executes Limits. Gate is recompiled with the new value, so its bytes change too.
        val limits = "src/main/java/dev/sample/Limits.java" to """
            package dev.sample;
            public class Limits {
                public static final int MAX = 10;
                public int max() { return MAX; }
            }
        """.trimIndent()
        val limit = "src/main/java/dev/sample/Limit.java" to """
            package dev.sample;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.RUNTIME) public @interface Limit { int value(); }
        """.trimIndent()
        val gate = "src/main/java/dev/sample/Gate.java" to """
            package dev.sample;
            public class Gate {
                @Limit(Limits.MAX)
                public String classify(int n) {
                    switch (n) {
                        case Limits.MAX: return "max";
                        default: return "other";
                    }
                }
            }
        """.trimIndent()
        val limitsTest = "src/test/java/dev/sample/LimitsTest.java" to """
            package dev.sample;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            class LimitsTest {
                @Test void max() { assertEquals(10, new Limits().max()); }
            }
        """.trimIndent()
        val gateTest = "src/test/java/dev/sample/GateTest.java" to """
            package dev.sample;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            class GateTest {
                @Test void atTheLimit() throws Exception {
                    assertEquals("max", new Gate().classify(10));
                    assertEquals(10, Gate.class.getMethod("classify", int.class).getAnnotation(Limit.class).value());
                }
            }
        """.trimIndent()
        build(dir, "build.gradle.kts" to minimalBuild, limits, limit, gate, limitsTest, gateTest)
        committed(dir)

        runner(dir, "test").build()
        val gateClass = File(dir, "build/classes/java/main/dev/sample/Gate.class")
        val before = gateClass.readBytes()
        File(dir, "build/test-results").deleteRecursively()
        File(dir, "src/main/java/dev/sample/Limits.java").writeText(
            limits.second.replace("MAX = 10", "MAX = 11")
        )
        commit(dir, "the constant moves")

        val output = runner(dir, "test", "-Pyoriwake.select").buildAndFail().output

        assertFalse(before.contentEquals(gateClass.readBytes()), "Gate was not recompiled with the new value")
        assertContains(output, "declares a compile-time constant")
        assertTrue("dev.sample.GateTest" in ranTests(dir), "GateTest executes the copy; ran ${ranTests(dir)}")
    }

    @Test
    fun `an inline function another production class inlined selects the tests that executed that class`(
        @TempDir dir: File,
    ) {
        val caller = "src/main/kotlin/dev/demokt/Caller.kt" to """
            package dev.demokt
            class Caller { fun check(n: Int): Boolean = aboveZero(n) }
        """.trimIndent()
        val callerTest = "src/test/kotlin/dev/demokt/CallerTest.kt" to """
            package dev.demokt
            import org.junit.jupiter.api.Test
            import kotlin.test.assertTrue
            class CallerTest {
                @Test fun positive() { assertTrue(Caller().check(1)) }
            }
        """.trimIndent()
        build(dir, "build.gradle.kts" to kotlinBuild, kotlinInlined, caller, callerTest, kotlinBelowTenTest,
            kotlinAlpha, kotlinAlphaTest)
        committed(dir)

        runner(dir, "test").build()
        val callerClass = File(dir, "build/classes/kotlin/main/dev/demokt/Caller.class")
        val before = callerClass.readBytes()
        File(dir, "build/test-results").deleteRecursively()
        File(dir, "src/main/kotlin/dev/demokt/Inlined.kt").writeText(
            kotlinInlined.second.replace("n > 0", "n >= 1")
        )
        commit(dir, "the inline body moves")

        val output = runner(dir, "test", "-Pyoriwake.select").build().output

        assertFalse(before.contentEquals(callerClass.readBytes()), "Caller was not recompiled with the new body")
        assertContains(output, "inlined the changed sources")
        assertTrue("dev.demokt.CallerTest" in ranTests(dir), "CallerTest executes the inlined body; ran ${ranTests(dir)}")
    }
}
