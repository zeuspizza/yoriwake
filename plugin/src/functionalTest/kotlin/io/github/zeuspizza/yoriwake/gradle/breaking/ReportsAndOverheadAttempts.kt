package io.github.zeuspizza.yoriwake.gradle.breaking

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Attempts on the wiring that carries the build's configuration to the test JVM. The plugin reads
 * its extension once every build script has run and hands each `Test` task what it found; a
 * setting that arrives by another route, or later, must still reach the test JVM.
 */
class ReportsAndOverheadAttempts : BreakAttempt() {

    private fun source(path: String, body: String) = path to body.trimIndent()

    private val codec = source(
        "src/main/java/dev/sample/Codec.java",
        """
        package dev.sample;
        public class Codec { public String encode(String s) { return new StringBuilder(s).reverse().toString(); } }
        """,
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

    private val codecTest = source(
        "src/test/java/dev/sample/A9CodecTest.java",
        """
        package dev.sample;
        import org.junit.jupiter.api.Test;
        import static org.junit.jupiter.api.Assertions.assertEquals;
        class A9CodecTest { @Test void reverses() { assertEquals("cba", new Codec().encode("abc")); } }
        """,
    )

    private val pinnedTest = source(
        "src/test/java/dev/sample/A1PinnedTest.java",
        """
        package dev.sample;
        import java.nio.file.Files;
        import java.nio.file.Paths;
        import org.junit.jupiter.api.Test;
        import static org.junit.jupiter.api.Assertions.assertTrue;
        class A1PinnedTest {
            @Test void readsTheCopy() throws Exception {
                assertTrue(Files.readString(Paths.get(System.getProperty("codec.copy"))).contains("reverse().toString()"));
            }
        }
        """,
    )

    private val copyingBuild = minimalBuild.replace(
        "tasks.test { useJUnitPlatform() }",
        """
        val copyCodec = tasks.register<Copy>("copyCodec") {
            from("src/main/java/dev/sample/Codec.java")
            rename { "codec.txt" }
            into(layout.buildDirectory.dir("copies"))
        }
        tasks.test {
            useJUnitPlatform()
            dependsOn(copyCodec)
            systemProperty("codec.copy", layout.buildDirectory.file("copies/codec.txt").get().asFile.absolutePath)
        }
        """.trimIndent(),
    )

    private fun changeCodec(dir: File) {
        val file = File(dir, codec.first)
        file.writeText(file.readText().replace("reverse()", "reverse().append(\"!\")"))
    }

    // Track: code reading. Boundary: the wiring reads the extension's pins in the plugin's own
    // afterEvaluate, registered when the plugins block applied it, and hands the test JVM what it
    // read then. A build script's afterEvaluate runs after the plugin's, so a pin it adds arrives
    // once the pins were already read.
    @Test
    fun `a pin added in the build script's afterEvaluate keeps its test`(@TempDir dir: File) {
        val result = attempt(
            dir, "dev.sample.A1PinnedTest", Aim.Row("always-run"),
            listOf(codec, other, first, pinnedTest, codecTest, classOrderByName),
            buildScript = copyingBuild + "\nafterEvaluate { yoriwake { alwaysRun.add(\"dev.sample.A1PinnedTest\") } }\n",
        ) { changeCodec(it) }

        assertOutcome(BreakAttempt.Outcome.HELD_BY_RULE, result)
    }
}
