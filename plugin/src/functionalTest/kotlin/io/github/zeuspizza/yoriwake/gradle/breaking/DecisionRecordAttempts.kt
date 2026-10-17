package io.github.zeuspizza.yoriwake.gradle.breaking

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Attempts on the decision record: every test JVM writes the rows it decided and the rules behind
 * each, and a reader must find the rule that kept each test, whichever JVM wrote it and whichever
 * JVM wrote last.
 */
class DecisionRecordAttempts : BreakAttempt() {

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

    private fun unrelated(name: String) = source(
        "src/test/java/dev/sample/$name.java",
        """
        package dev.sample;
        import org.junit.jupiter.api.Test;
        import static org.junit.jupiter.api.Assertions.assertEquals;
        class $name { @Test void runs() { assertEquals(1, new Other().one()); } }
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

    private fun changeCodec(dir: File) {
        val file = File(dir, codec.first)
        file.writeText(file.readText().replace("reverse()", "reverse().append(\"!\")"))
    }

    // Track: code reading. Boundary: each test JVM writes its record at exit, to the shared file and
    // to a part of its own; the shared file is whichever JVM wrote last. With every class in its own
    // JVM, the target's JVM is not the last, so its row and rule line survive only in its part.
    @Test
    fun `a test whose JVM is not the record's last writer keeps its row and rule in its own part`(@TempDir dir: File) {
        val target = source(
            "src/test/java/dev/sample/A1TargetTest.java",
            """
            package dev.sample;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            class A1TargetTest { @Test void reverses() { assertEquals("cba", new Codec().encode("abc")); } }
            """,
        )
        val result = attempt(
            dir, "dev.sample.A1TargetTest", Aim.Row("reaches-change"),
            listOf(codec, other, unrelated("A0UnrelatedTest"), target, unrelated("B1LaterTest"), unrelated("B2LastTest")),
            buildScript = minimalBuild.replace("tasks.test { useJUnitPlatform() }", "tasks.test { useJUnitPlatform(); forkEvery = 1 }"),
        ) { changeCodec(it) }

        assertOutcome(BreakAttempt.Outcome.HELD_BY_RULE, result)
    }

    // Track: code reading. Boundary: the record's JVM-wide windows open at the first touch of a
    // changed class in each JVM, whatever the touch was. A lookup by name that loads a class without
    // initialising it executes none of it, so no coverage records it; the later test that depends on
    // the class through the build directory must still carry the window's rule.
    @Test
    fun `a test after a lookup that only loads the changed class carries the JVM-wide rule`(@TempDir dir: File) {
        val lookup = source(
            "src/test/java/dev/sample/A1LookupTest.java",
            """
            package dev.sample;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            class A1LookupTest {
                @Test void finds() throws Exception {
                    assertEquals("Codec", Class.forName("dev.sample.Codec", false, getClass().getClassLoader()).getSimpleName());
                }
            }
            """,
        )
        val target = source(
            "src/test/java/dev/sample/A2TargetTest.java",
            """
            package dev.sample;
            import java.nio.file.Files;
            import java.nio.file.Paths;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertTrue;
            class A2TargetTest {
                @Test void readsTheCopy() throws Exception {
                    assertTrue(Files.readString(Paths.get(System.getProperty("codec.copy"))).contains("reverse().toString()"));
                }
            }
            """,
        )
        val build = minimalBuild.replace(
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
        val result = attempt(
            dir, "dev.sample.A2TargetTest", Aim.Row("shares-jvm-changed-class"),
            listOf(codec, other, unrelated("A0UnrelatedTest"), lookup, target, codecTest, classOrderByName),
            buildScript = build,
        ) { changeCodec(it) }

        assertOutcome(BreakAttempt.Outcome.HELD_BY_RULE, result)
    }
}
