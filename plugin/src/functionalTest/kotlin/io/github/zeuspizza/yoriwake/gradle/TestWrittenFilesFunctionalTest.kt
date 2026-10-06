package io.github.zeuspizza.yoriwake.gradle

import io.github.zeuspizza.yoriwake.agent.contract.AgentContract
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A file in the source tree that the build or its own tests write keeps forcing every selecting
 * run, whether a test, a task or a hand wrote it, and wherever it lies.
 */
class TestWrittenFilesFunctionalTest : FunctionalTestSupport() {

    private fun app(dir: File) = File(dir, "app")

    /**
     * Alpha and Beta with their tests in an `app` module, committed. The settings file names `app`,
     * so every path under it is one a build script names and none is discharged as unreadable.
     */
    private fun fixture(
        dir: File,
        appBuild: String = "",
        vararg extra: Pair<String, String>,
    ) {
        build(
            dir,
            "build.gradle.kts" to "",
            "app/build.gradle.kts" to minimalBuild + "\n" + appBuild.trimIndent(),
            *arrayOf(oneClass, oneTest, secondClass, secondTest, classOrderByName, *extra)
                .map { (path, content) -> "app/$path" to content }.toTypedArray(),
        )
        File(dir, "settings.gradle.kts").writeText("rootProject.name = \"sample\"\ninclude(\"app\")\n")
        ignoreBuildOutputs(dir)
        File(dir, ".gitignore").appendText("\n*.log\nstate.txt\ngenerated.txt\ngen/\n")
        git(dir, "init")
        commit(dir, "base")
    }

    private fun capture(dir: File, vararg args: String) {
        runner(dir, ":app:test", *args).build()
    }

    private fun select(dir: File, vararg args: String): String {
        File(app(dir), "build/test-results").deleteRecursively()
        return runner(dir, ":app:test", "-Pyoriwake.select", *args).build().output
    }

    private val logWriterTest = "src/test/java/dev/sample/AaWritesLogTest.java" to """
        package dev.sample;
        import org.junit.jupiter.api.Test;
        import java.nio.file.Files;
        import java.nio.file.Path;
        class AaWritesLogTest {
            @Test void logs() throws Exception {
                Files.writeString(Path.of(System.getProperty("log.file", "run.log")), "ran at " + System.currentTimeMillis());
            }
        }
    """.trimIndent()

    private val withLogWriter = setOf("dev.sample.AaWritesLogTest", "dev.sample.AlphaTest", "dev.sample.BetaTest")

    @Test
    fun `a file a test writes during the capture forces the next selecting run`(@TempDir dir: File) {
        fixture(dir, extra = arrayOf(logWriterTest))
        capture(dir)
        changeBeta(app(dir))

        val output = select(dir)

        assertEquals(withLogWriter, ranTests(app(dir)), output)
        assertEquals("unmappable-paths", decisionNotes(dir)[AgentContract.FULL_RUN_KIND_NOTE], output)
    }

    @Test
    fun `a file one test writes and a later test reads runs the reader after a hand edit`(@TempDir dir: File) {
        fixture(
            dir,
            extra = arrayOf(
                "src/main/java/dev/sample/Legacy.java" to """
                    package dev.sample;
                    public class Legacy { public int value() { return 1; } }
                """.trimIndent(),
                "src/test/java/dev/sample/AaStateWriterTest.java" to """
                    package dev.sample;
                    import org.junit.jupiter.api.Test;
                    import java.nio.file.Files;
                    import java.nio.file.Path;
                    class AaStateWriterTest {
                        @Test void writes() throws Exception { Files.writeString(Path.of("state.txt"), "y"); }
                    }
                """.trimIndent(),
                "src/test/java/dev/sample/AbStateReaderTest.java" to """
                    package dev.sample;
                    import org.junit.jupiter.api.Test;
                    import java.nio.file.Files;
                    import java.nio.file.Path;
                    import static org.junit.jupiter.api.Assertions.assertEquals;
                    class AbStateReaderTest {
                        @Test void reads() throws Exception {
                            if (Files.readString(Path.of("state.txt")).equals("x")) assertEquals(1, new Legacy().value());
                        }
                    }
                """.trimIndent(),
            ),
        )
        capture(dir)
        File(app(dir), "state.txt").writeText("x")
        File(app(dir), "src/main/java/dev/sample/Legacy.java").writeText(
            """
            package dev.sample;
            public class Legacy { public int value() { return 2 - 1; } }
            """.trimIndent()
        )

        val output = select(dir)

        assertEquals(
            setOf("dev.sample.AaStateWriterTest", "dev.sample.AbStateReaderTest", "dev.sample.AlphaTest", "dev.sample.BetaTest"),
            ranTests(app(dir)),
            output,
        )
        assertEquals("unmappable-paths", decisionNotes(dir)[AgentContract.FULL_RUN_KIND_NOTE], output)
    }

    @Test
    fun `a file a test created during the capture forces once deleted, as in a fresh checkout`(@TempDir dir: File) {
        fixture(dir, extra = arrayOf(logWriterTest))
        capture(dir)
        File(app(dir), "run.log").delete()
        changeBeta(app(dir))

        val output = select(dir)

        assertEquals(withLogWriter, ranTests(app(dir)), output)
        assertEquals("unmappable-paths", decisionNotes(dir)[AgentContract.FULL_RUN_KIND_NOTE], output)
    }

    /** Writes an ignored resource into a main resource directory from the `info` property. */
    private val infoGenerator = """
        val generateInfo by tasks.registering {
            val info = providers.gradleProperty("info").orElse("a")
            val out = layout.projectDirectory.file("gen/main/resources/info.properties")
            inputs.property("info", info)
            outputs.file(out)
            doLast { out.asFile.apply { parentFile.mkdirs() }.writeText("info=" + info.get() + "\n") }
        }
        sourceSets.main { resources.srcDir("gen/main/resources") }
        tasks.processResources { dependsOn(generateInfo) }
    """

    @Test
    fun `a resource the selecting build regenerates into a source directory refuses the run as stale`(
        @TempDir dir: File,
    ) {
        // The build before the capture leaves the resource present and its generator up to date,
        // so the capture starts and ends with the same file.
        fixture(dir, infoGenerator)
        runner(dir, ":app:processResources", "-Pinfo=a").build()
        capture(dir, "-Pinfo=a")
        changeBeta(app(dir))

        val output = select(dir, "-Pinfo=b")

        assertEquals(both, ranTests(app(dir)), output)
        assertEquals("change-set-stale", decisionNotes(dir)[AgentContract.REFUSAL_KIND_NOTE], output)
    }

    @Test
    fun `a resource an earlier build regenerated into a source directory forces the selecting run`(
        @TempDir dir: File,
    ) {
        fixture(dir, infoGenerator)
        runner(dir, ":app:processResources", "-Pinfo=a").build()
        capture(dir, "-Pinfo=a")
        runner(dir, ":app:processResources", "-Pinfo=b").build()
        changeBeta(app(dir))

        val output = select(dir, "-Pinfo=b")

        assertEquals(both, ranTests(app(dir)), output)
        assertEquals("unmappable-paths", decisionNotes(dir)[AgentContract.FULL_RUN_KIND_NOTE], output)
    }

    @Test
    fun `a file a build task writes outside the source directories before the tests forces the selecting run`(
        @TempDir dir: File,
    ) {
        fixture(
            dir,
            """
            val writeGenerated by tasks.registering {
                val out = layout.projectDirectory.file("generated.txt")
                doLast { out.asFile.writeText("generated") }
            }
            tasks.test { dependsOn(writeGenerated) }
            """,
        )
        capture(dir)
        changeBeta(app(dir))

        val output = select(dir)

        assertEquals(both, ranTests(app(dir)), output)
        assertEquals("unmappable-paths", decisionNotes(dir)[AgentContract.FULL_RUN_KIND_NOTE], output)
    }

    @Test
    fun `an untracked fixture added after the capture forces the selecting run`(@TempDir dir: File) {
        fixture(dir)
        capture(dir)
        File(app(dir), "fixtures/data.json").apply { parentFile.mkdirs() }.writeText("{}")
        changeBeta(app(dir))

        val output = select(dir)

        assertEquals(both, ranTests(app(dir)), output)
        assertEquals("unmappable-paths", decisionNotes(dir)[AgentContract.FULL_RUN_KIND_NOTE], output)
    }

    @Test
    fun `a tracked golden file a test rewrites, then edited and committed, forces the selecting run`(
        @TempDir dir: File,
    ) {
        fixture(
            dir,
            extra = arrayOf(
                "golden.txt" to "expected",
                "src/test/java/dev/sample/AaGoldenTest.java" to """
                    package dev.sample;
                    import org.junit.jupiter.api.Test;
                    import java.nio.file.Files;
                    import java.nio.file.Path;
                    class AaGoldenTest {
                        @Test void rewrites() throws Exception { Files.writeString(Path.of("golden.txt"), "expected"); }
                    }
                """.trimIndent(),
            ),
        )
        capture(dir)
        File(app(dir), "golden.txt").writeText("expected again")
        commit(dir, "golden")
        changeBeta(app(dir))

        val output = select(dir)

        assertEquals(
            setOf("dev.sample.AaGoldenTest", "dev.sample.AlphaTest", "dev.sample.BetaTest"),
            ranTests(app(dir)),
            output,
        )
        assertEquals("unmappable-paths", decisionNotes(dir)[AgentContract.FULL_RUN_KIND_NOTE], output)
    }

    @Test
    fun `a test that writes its file under the build directory leaves the selecting run narrowed`(
        @TempDir dir: File,
    ) {
        fixture(
            dir,
            """
            tasks.test { systemProperty("log.file", layout.buildDirectory.file("run.log").get().asFile.absolutePath) }
            """,
            logWriterTest,
        )
        capture(dir)
        changeBeta(app(dir))

        val output = select(dir)

        assertEquals(setOf("dev.sample.BetaTest"), ranTests(app(dir)), output)
        assertNull(decisionNotes(dir)[AgentContract.FULL_RUN_KIND_NOTE], output)
        assertNull(decisionNotes(dir)[AgentContract.REFUSAL_KIND_NOTE], output)
    }

    private val both = setOf("dev.sample.AlphaTest", "dev.sample.BetaTest")
}
