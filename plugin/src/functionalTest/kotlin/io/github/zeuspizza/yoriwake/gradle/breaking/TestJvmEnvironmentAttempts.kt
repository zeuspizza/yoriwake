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

    // Track: code reading. Boundary: the fingerprint reads the task's JVM arguments as Gradle hands
    // them to the test JVM, argument providers included, which are asked for their arguments only
    // when the task runs.
    @Test
    fun `a system property an argument provider passes runs everything when it changed`(@TempDir dir: File) {
        val build = buildWith(
            "jvmArgumentProviders.add(TreeStateArgument(treeState))",
            before = """
            class TreeStateArgument(@get:Input val state: Provider<String>) : CommandLineArgumentProvider {
                override fun asArguments() = listOf("-Dtree.state=" + state.get())
            }
            """,
        )
        val result = attempt(
            dir, "dev.sample.A1TreeStateTest", Aim.Forced("environment-changed"),
            listOf(codec, other, first, propertyTest, codecTest, classOrderByName), buildScript = build,
        ) { changeCodec(it) }

        assertOutcome(BreakAttempt.Outcome.HELD_BY_FORCE, result)
    }

    // Track: code reading. Boundary: a capture writes the fingerprint it starts with to a pending
    // file the decode folds into the map; a capture whose pending file is gone by then writes no
    // row for its test JVMs, and the next selecting run must refuse as unrecorded rather than
    // compare against nothing. A build's own doFirst runs after yoriwake's and removes it.
    @Test
    fun `a capture whose pending fingerprint a doFirst removes runs everything as unrecorded`(@TempDir dir: File) {
        val codecRunner = source(
            "src/test/java/dev/sample/A1CodecTest.java",
            """
            package dev.sample;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            class A1CodecTest { @Test void reverses() { assertEquals("cba", new Codec().encode("abc")); } }
            """,
        )
        val build = buildWith(
            """
            val state = File(rootDir, ".gradle/yoriwake")
            doFirst { state.walkTopDown().filter { it.name == "environment.pending" }.forEach { it.delete() } }
            """,
        )
        val result = attempt(
            dir, "dev.sample.A1CodecTest", Aim.Forced("environment-unrecorded"),
            listOf(codec, other, first, codecRunner, classOrderByName), buildScript = build,
        ) { changeCodec(it) }

        assertOutcome(BreakAttempt.Outcome.HELD_BY_FORCE, result)
    }

    // Track: fixtures. Row: EV1.
    @Test
    fun `a test that needs Java 21 selecting on a Java 17 test JVM runs everything`(@TempDir dir: File) {
        val featureTest = source(
            "src/test/java/dev/sample/A1FeatureTest.java",
            """
            package dev.sample;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertTrue;
            class A1FeatureTest { @Test void recent() { assertTrue(Runtime.version().feature() >= 21, Runtime.version().toString()); } }
            """,
        )
        val build = buildWith(
            """
            javaLauncher.set(javaToolchains.launcherFor {
                languageVersion.set(treeState.map { JavaLanguageVersion.of(if (it == "clean") 21 else 17) })
            })
            """,
            after = "tasks.withType<JavaCompile>().configureEach { options.release.set(11) }",
        )
        val result = attempt(
            dir, "dev.sample.A1FeatureTest", Aim.Forced("environment-changed"),
            listOf(
                codec, other, first, featureTest, codecTest, classOrderByName,
                "gradle.properties" to "org.gradle.java.installations.auto-download=false\n",
            ),
            buildScript = build,
        ) { changeCodec(it) }

        assertOutcome(BreakAttempt.Outcome.HELD_BY_FORCE, result)
    }

    // Track: fixtures. Row: EV2.
    @Test
    fun `a system property of the test task with another value runs everything`(@TempDir dir: File) {
        val result = attempt(
            dir, "dev.sample.A1TreeStateTest", Aim.Forced("environment-changed"),
            listOf(codec, other, first, propertyTest, codecTest, classOrderByName),
            buildScript = buildWith("systemProperty(\"tree.state\", treeState.get())"),
        ) { changeCodec(it) }

        assertOutcome(BreakAttempt.Outcome.HELD_BY_FORCE, result)
    }

    // Track: fixtures. Row: EV3.
    @Test
    fun `a listed environment variable with another value runs everything`(@TempDir dir: File) {
        val variableTest = source(
            "src/test/java/dev/sample/A1TreeVariableTest.java",
            """
            package dev.sample;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            class A1TreeVariableTest { @Test void clean() { assertEquals("clean", System.getenv("TREE_STATE")); } }
            """,
        )
        val result = attempt(
            dir, "dev.sample.A1TreeVariableTest", Aim.Forced("environment-changed"),
            listOf(codec, other, first, variableTest, codecTest, classOrderByName),
            buildScript = buildWith(
                "environment(\"TREE_STATE\", treeState.get())",
                after = "yoriwake { environmentInclude.add(\"TREE_STATE\") }",
            ),
        ) { changeCodec(it) }

        assertOutcome(BreakAttempt.Outcome.HELD_BY_FORCE, result)
    }
}
