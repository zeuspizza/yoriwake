package io.github.zeuspizza.yoriwake.gradle.breaking

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Attempts on the test JVM's environment: the JDK, the system properties the task passes and the
 * variables a build lists. A test can take another path in another environment, so a selecting run
 * whose environment differs from the map's runs every test; each attempt here moves the
 * environment with the working tree, by a value the build derives from whether the tree is clean.
 */
class TestJvmEnvironmentAttempts : BreakAttempt() {

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

    private fun changeCodec(dir: File) {
        val file = File(dir, codec.first)
        file.writeText(file.readText().replace("reverse()", "reverse().append(\"!\")"))
    }

    /** A test that passes only on a clean tree, as the system property `tree.state` reports it. */
    private val propertyTest = source(
        "src/test/java/dev/sample/A1TreeStateTest.java",
        """
        package dev.sample;
        import org.junit.jupiter.api.Test;
        import static org.junit.jupiter.api.Assertions.assertEquals;
        class A1TreeStateTest { @Test void clean() { assertEquals("clean", System.getProperty("tree.state")); } }
        """,
    )

    /** `clean` while the working tree has no change, `dirty` otherwise; read when the build is configured. */
    private val treeState = """
        val treeState = providers.exec { commandLine("git", "status", "--porcelain") }
            .standardOutput.asText.map { if (it.isBlank()) "clean" else "dirty" }
    """.trimIndent()

    /** [minimalBuild] with [before] above the test task's block, [extra] inside it, and [after] below it. */
    private fun buildWith(extra: String, before: String = "", after: String = "") = minimalBuild.replace(
        "tasks.test { useJUnitPlatform() }",
        before.trimIndent() + "\n" + treeState + "\n" +
            "tasks.test {\n    useJUnitPlatform()\n" + extra.trimIndent().prependIndent("    ") + "\n}\n" + after.trimIndent(),
    )

    // Track: code reading. Boundary: the fingerprint takes system properties from the task's JVM
    // arguments that start with `-D`. The java launcher also reads arguments from an `@file`, so a
    // property the build passes through an argument file is set in the test JVM and never compared.
    @Test
    fun `a system property passed through a JVM argument file runs everything when it changed`(@TempDir dir: File) {
        val build = buildWith(
            """
            dependsOn("stampArgs")
            jvmArgs("@" + layout.buildDirectory.file("gen/test.args").get().asFile.absolutePath)
            """,
            after = """
            tasks.register("stampArgs") {
                val out = layout.buildDirectory.file("gen/test.args")
                val state = treeState
                inputs.property("state", state)
                outputs.file(out)
                doLast { out.get().asFile.apply { parentFile.mkdirs() }.writeText("-Dtree.state=" + state.get() + "\n") }
            }
            """,
        )
        val result = attempt(
            dir, "dev.sample.A1TreeStateTest", Aim.Forced("environment-changed"),
            listOf(codec, other, first, propertyTest, codecTest, classOrderByName), buildScript = build,
        ) { changeCodec(it) }

        assertOutcome(BreakAttempt.Outcome.HELD_BY_FORCE, result)
    }
}
