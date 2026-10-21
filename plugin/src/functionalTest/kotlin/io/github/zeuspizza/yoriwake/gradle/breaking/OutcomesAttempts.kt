package io.github.zeuspizza.yoriwake.gradle.breaking

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Attempts on what the map records of each test besides its coverage: whether it is known to
 * pass, whether the map has seen it at all, and whether the build pinned it. Each of these runs a
 * test whatever the change, so each target here depends on the change in a way coverage cannot
 * see, and only the outcome rule can keep it.
 */
class OutcomesAttempts : BreakAttempt() {

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

    private fun first(pkg: String = "dev.sample") = source(
        "src/test/java/${pkg.replace('.', '/')}/A0UnrelatedTest.java",
        """
        package $pkg;
        import dev.sample.Other;
        import org.junit.jupiter.api.Test;
        import static org.junit.jupiter.api.Assertions.assertEquals;
        class A0UnrelatedTest { @Test void runsFirst() { assertEquals(1, new Other().one()); } }
        """,
    )

    private fun codecTest(pkg: String = "dev.sample") = source(
        "src/test/java/${pkg.replace('.', '/')}/A9CodecTest.java",
        """
        package $pkg;
        import dev.sample.Codec;
        import org.junit.jupiter.api.Test;
        import static org.junit.jupiter.api.Assertions.assertEquals;
        class A9CodecTest { @Test void reverses() { assertEquals("cba", new Codec().encode("abc")); } }
        """,
    )

    private fun changeCodec(dir: File) {
        val file = File(dir, codec.first)
        file.writeText(file.readText().replace("reverse()", "reverse().append(\"!\")"))
    }

    /** As [minimalBuild], with Codec's source copied to a file no rule dates, and [testConfig] on the task. */
    private fun copyingBuild(testConfig: String = "") = minimalBuild.replace(
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
            $testConfig
        }
        """.trimIndent(),
    )

    /** The assertion that fails once Codec's source no longer reverses plainly. */
    private val readsCopy =
        """assertTrue(Files.readString(Paths.get(System.getProperty("codec.copy"))).contains("reverse().toString()"));"""

    private fun copyReader(pkg: String, name: String) = source(
        "src/test/java/${pkg.replace('.', '/')}/$name.java",
        """
        package $pkg;
        import java.nio.file.Files;
        import java.nio.file.Paths;
        import org.junit.jupiter.api.Test;
        import static org.junit.jupiter.api.Assertions.assertTrue;
        class $name {
            @Test void readsTheCopy() throws Exception { $readsCopy }
        }
        """,
    )

    // Track: code reading. Boundary: the decoder reads a test record with no in-scope class as
    // unknown, not as a test that covers nothing. A host's own JaCoCo includes set the scope, and a
    // test class outside them, which runs nothing inside them, records no class at all.
    @Test
    fun `a test whose record holds no in-scope class is held as not known to pass`(@TempDir dir: File) {
        val result = attempt(
            dir, "dev.tests.A1ScopelessTest", Aim.Row("not-known-to-pass"),
            listOf(codec, other, first("dev.tests"), copyReader("dev.tests", "A1ScopelessTest"), codecTest("dev.tests"),
                classOrderByName),
            buildScript = copyingBuild(
                "extensions.getByType<JacocoTaskExtension>().includes = listOf(\"dev.sample.*\")",
            ),
        ) { changeCodec(it) }

        assertOutcome(BreakAttempt.Outcome.HELD_BY_RULE, result)
    }

    // Track: code reading. Boundary: the map knows a test by its exact id, or a template by the
    // invocations beneath it. Jupiter runs an interface's default test methods as tests of every
    // class implementing it, so renaming one in the interface gives an unchanged test class a test
    // the map has never seen, whose id no recorded one extends.
    @Test
    fun `a test renamed in the interface its class implements is held as a test the map never saw`(@TempDir dir: File) {
        val contract = source(
            "src/test/java/dev/sample/ReversingContract.java",
            """
            package dev.sample;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            interface ReversingContract {
                // On a line of its own: a method line led by an annotation is not read as executable
                // code, and an interface with none is a path coverage cannot see.
                @Test
                default void reverses() { assertEquals("cba", new Codec().encode("abc")); }
            }
            """,
        )
        val target = source(
            "src/test/java/dev/sample/A1ContractTest.java",
            "package dev.sample;\nclass A1ContractTest implements ReversingContract {}",
        )
        val result = attempt(
            dir, "dev.sample.A1ContractTest", Aim.Row("not-in-map"),
            listOf(codec, other, first(), contract, target),
            buildScript = minimalBuild.replace("tasks.test { useJUnitPlatform() }", "tasks.test { useJUnitPlatform(); forkEvery = 1 }"),
        ) {
            val file = File(it, contract.first)
            file.writeText(
                file.readText().replace(
                    "default void reverses() { assertEquals(\"cba\", new Codec().encode(\"abc\")); }",
                    "default void reversesBack() { assertEquals(\"abc!\", new Codec().encode(\"cba\")); }",
                ),
            )
        }

        assertOutcome(BreakAttempt.Outcome.HELD_BY_RULE, result)
    }

    // Track: fixtures. Row: OR6.
    @Test
    fun `a test a glob pins is held as always run though the change cannot reach it`(@TempDir dir: File) {
        val result = attempt(
            dir, "dev.sample.A1PinnedTest", Aim.Row("always-run"),
            listOf(codec, other, first(), copyReader("dev.sample", "A1PinnedTest"), codecTest(), classOrderByName),
            buildScript = copyingBuild() + "\nyoriwake { alwaysRun.add(\"dev.sample.A1Pin*\") }\n",
        ) { changeCodec(it) }

        assertOutcome(BreakAttempt.Outcome.HELD_BY_RULE, result)
    }

    // Track: code reading. Boundary: a pin is matched against a name built from the test's id, the
    // last class segment and the method. A @Nested class's segment holds its simple name only, so a
    // pattern naming the enclosing class, which pins that class's own methods, never matches the
    // tests of its nested class; and because it matched something, nothing reports it.
    @Test
    fun `a pin on a class keeps the tests of its nested class`(@TempDir dir: File) {
        val target = source(
            "src/test/java/dev/sample/A1PinnedTest.java",
            """
            package dev.sample;
            import java.nio.file.Files;
            import java.nio.file.Paths;
            import org.junit.jupiter.api.Nested;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertTrue;
            class A1PinnedTest {
                @Test void outer() { assertTrue(true); }
                @Nested class Inner {
                    @Test void readsTheCopy() throws Exception { $readsCopy }
                }
            }
            """,
        )
        val result = attempt(
            dir, "dev.sample.A1PinnedTest\$Inner", Aim.Row("always-run"),
            listOf(codec, other, first(), target, codecTest(), classOrderByName),
            buildScript = copyingBuild() + "\nyoriwake { alwaysRun.add(\"dev.sample.A1PinnedTest\") }\n",
        ) { changeCodec(it) }

        assertOutcome(BreakAttempt.Outcome.HELD_BY_RULE, result)
    }

    /** Runs after a target and before Codec's test, long enough for --fail-fast to stop its JVM. */
    private val slowTest = source(
        "src/test/java/dev/sample/A5SlowTest.java",
        """
        package dev.sample;
        import org.junit.jupiter.api.Test;
        class A5SlowTest { @Test void waits() throws Exception { Thread.sleep(Boolean.getBoolean("sample.slow") ? 20000 : 0); } }
        """,
    )

    // Track: fixtures. Row: a test that fails just before --fail-fast stops its JVM. The run cannot
    // date the map, so what it keeps is the failure: a test that failed runs until a capture sees
    // it pass. A slow test after the target keeps the JVM busy until Gradle stops it.
    @Test
    fun `a test that failed before --fail-fast stopped its JVM is held as not known to pass`(@TempDir dir: File) {
        val result = attempt(
            dir, "dev.sample.A1FailFastTest", Aim.Row("not-known-to-pass"),
            listOf(codec, other, first(), copyReader("dev.sample", "A1FailFastTest"), slowTest, codecTest(), classOrderByName),
            buildScript = copyingBuild("if (project.hasProperty(\"slow\")) systemProperty(\"sample.slow\", \"true\")"),
        ) {
            changeCodec(it)
            runner(it, "test", "--fail-fast", "-Pslow").buildAndFail()
            File(it, "build/test-results").deleteRecursively()
        }

        assertOutcome(BreakAttempt.Outcome.HELD_BY_RULE, result)
    }

    // Track: code reading. Boundary: the agent writes a test's outcome in its own listener, after
    // JUnit has told the listeners before it, and a failure goes to disk at once rather than with
    // the batch the shutdown hook flushes. Here a listener of the suite's own halts the JVM as soon
    // as a test fails, so no shutdown hook runs and nothing after the failure is written; Gradle's
    // JUnit XML report is off for that run, so the agent's own record is the only account of it.
    @Test
    fun `a test that failed just before a listener halted its JVM is held as not known to pass`(@TempDir dir: File) {
        val listener = source(
            "src/test/java/dev/sample/HaltOnFailure.java",
            """
            package dev.sample;
            import org.junit.platform.engine.TestExecutionResult;
            import org.junit.platform.launcher.TestExecutionListener;
            import org.junit.platform.launcher.TestIdentifier;
            public class HaltOnFailure implements TestExecutionListener {
                @Override
                public void executionFinished(TestIdentifier id, TestExecutionResult result) {
                    if (Boolean.getBoolean("sample.halt") && result.getStatus() != TestExecutionResult.Status.SUCCESSFUL) {
                        Runtime.getRuntime().halt(3);
                    }
                }
            }
            """,
        )
        val registration = "src/test/resources/META-INF/services/org.junit.platform.launcher.TestExecutionListener" to
            "dev.sample.HaltOnFailure\n"
        val result = attempt(
            dir, "dev.sample.A1HaltedTest", Aim.Row("not-known-to-pass"),
            listOf(codec, other, first(), copyReader("dev.sample", "A1HaltedTest"), codecTest(), listener, registration,
                classOrderByName),
            buildScript = copyingBuild(
                "if (project.hasProperty(\"halt\")) { systemProperty(\"sample.halt\", \"true\"); reports.junitXml.required = false }",
            )
                .replace("testRuntimeOnly(\"org.junit.platform:junit-platform-launcher\")",
                    "testImplementation(\"org.junit.platform:junit-platform-launcher\")"),
        ) {
            changeCodec(it)
            runner(it, "test", "-Phalt").buildAndFail()
            File(it, "build/test-results").deleteRecursively()
        }

        assertOutcome(BreakAttempt.Outcome.HELD_BY_RULE, result)
    }

    // Track: code reading. Boundary: a tag pins by the descriptor's tags, read only on a leaf after
    // the container check. Jupiter gathers a test's tags from its method and every descriptor above
    // it; here the tag is carried by a composed annotation on the enclosing class of a @Nested class.
    @Test
    fun `a tag a composed annotation puts on the enclosing class keeps the tests of its nested class`(@TempDir dir: File) {
        val annotation = source(
            "src/test/java/dev/sample/Pinned.java",
            """
            package dev.sample;
            import java.lang.annotation.*;
            import org.junit.jupiter.api.Tag;
            @Target(ElementType.TYPE) @Retention(RetentionPolicy.RUNTIME) @Tag("yoriwake-always-run")
            @interface Pinned {}
            """,
        )
        val target = source(
            "src/test/java/dev/sample/A1PinnedTest.java",
            """
            package dev.sample;
            import java.nio.file.Files;
            import java.nio.file.Paths;
            import org.junit.jupiter.api.Nested;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertTrue;
            @Pinned
            class A1PinnedTest {
                @Nested class Inner {
                    @Test void readsTheCopy() throws Exception { $readsCopy }
                }
            }
            """,
        )
        val result = attempt(
            dir, "dev.sample.A1PinnedTest\$Inner", Aim.Row("always-run"),
            listOf(codec, other, first(), annotation, target, codecTest(), classOrderByName),
            buildScript = copyingBuild(),
        ) { changeCodec(it) }

        assertOutcome(BreakAttempt.Outcome.HELD_BY_RULE, result)
    }
}
