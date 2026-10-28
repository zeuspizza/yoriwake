package io.github.zeuspizza.yoriwake.gradle.breaking

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Attempts on the point from which a child process makes a JVM's touches unobservable. What a child
 * reads or runs is out of the agent's sight, so every later test in that JVM runs after any change.
 */
class ChildProcessAttempts : BreakAttempt() {

    private fun source(path: String, body: String) = path to body.trimIndent()

    private val codec = source(
        "src/main/java/dev/sample/Codec.java",
        "package dev.sample;\npublic class Codec { public String encode(String s) { return new StringBuilder(s).reverse().toString(); } }",
    )

    private val other = source(
        "src/main/java/dev/sample/Other.java",
        "package dev.sample;\npublic class Other { public int one() { return 1; } }",
    )

    private val first = source(
        "src/test/java/dev/sample/A0UnrelatedTest.java",
        """
        package dev.sample;
        import org.junit.jupiter.api.Test;
        import static org.junit.jupiter.api.Assertions.assertEquals;
        class A0UnrelatedTest { @Test void runsFirst() { assertEquals(1, new Other().one()); } }
        """,
    )

    /** Executes Codec, so the change to it is covered and does not force a full run. */
    private val codecTest = source(
        "src/test/java/dev/sample/A9CodecTest.java",
        """
        package dev.sample;
        import org.junit.jupiter.api.Test;
        import static org.junit.jupiter.api.Assertions.assertEquals;
        class A9CodecTest { @Test void reverses() { assertEquals("cba", new Codec().encode("abc")); } }
        """,
    )

    /** Codec's class file copied to a name that is not a class file: reading it dates nothing. */
    private val copiedCodecBuild = minimalBuild.replace(
        "tasks.test { useJUnitPlatform() }",
        """
        val copyCodec = tasks.register<Copy>("copyCodec") {
            dependsOn(tasks.compileJava)
            from(layout.buildDirectory.file("classes/java/main/dev/sample/Codec.class"))
            rename { "Codec.bytes" }
            into(layout.buildDirectory.dir("copies"))
        }
        tasks.test {
            useJUnitPlatform()
            dependsOn(copyCodec)
            systemProperty("codec.copy", layout.buildDirectory.file("copies/Codec.bytes").get().asFile.absolutePath)
        }
        """.trimIndent(),
    )

    /** Reads Codec's bytes as data and fails once the change adds "append" to them: never executes Codec. */
    private val reader = source(
        "src/test/java/dev/sample/A2ReaderTest.java",
        """
        package dev.sample;
        import java.nio.charset.StandardCharsets;
        import java.nio.file.Files;
        import java.nio.file.Paths;
        import org.junit.jupiter.api.Test;
        import static org.junit.jupiter.api.Assertions.assertFalse;
        class A2ReaderTest {
            @Test void noAppend() throws Exception {
                byte[] bytes = Files.readAllBytes(Paths.get(System.getProperty("codec.copy")));
                assertFalse(new String(bytes, StandardCharsets.ISO_8859_1).contains("append"));
            }
        }
        """,
    )

    private fun changeCodec(dir: File) {
        val file = File(dir, codec.first)
        file.writeText(file.readText().replace("reverse()", "reverse().append(\"!\")"))
    }

    // Track: code reading. Boundary: ReadHooks.PROCESSES hooks only ProcessImpl.start, the one place
    // every child start bottoms out. ProcessBuilder.startPipeline is a third public entry, distinct
    // from ProcessBuilder.start and Runtime.exec, and it too reaches ProcessImpl.start, so a
    // pipeline of children makes the JVM unobserved as a single child does.
    @Test
    fun `a pipeline of child processes makes its JVM unobserved from that point`(@TempDir dir: File) {
        val pipeline = source(
            "src/test/java/dev/sample/A1PipelineTest.java",
            """
            package dev.sample;
            import java.lang.ProcessBuilder;
            import java.util.List;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            class A1PipelineTest {
                @Test void runs() throws Exception {
                    List<Process> stages = ProcessBuilder.startPipeline(List.of(
                        new ProcessBuilder("/bin/echo", "hi"),
                        new ProcessBuilder("/bin/cat")));
                    for (Process p : stages) assertEquals(0, p.waitFor());
                }
            }
            """,
        )
        val result = attempt(
            dir, "dev.sample.A2ReaderTest", Aim.Row("shares-jvm-unobserved"),
            listOf(codec, other, first, pipeline, reader, codecTest, classOrderByName),
            buildScript = copiedCodecBuild,
        ) { changeCodec(it) }

        assertOutcome(Outcome.HELD_BY_RULE, result)
    }
}
