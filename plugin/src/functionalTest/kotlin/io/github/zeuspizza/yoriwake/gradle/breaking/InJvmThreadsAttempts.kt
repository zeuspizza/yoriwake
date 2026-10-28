package io.github.zeuspizza.yoriwake.gradle.breaking

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Attempts on threads that outlive the test that started them, and on tests interleaved in one
 * JVM. A thread's work lands in whichever window is open when it runs, and a class it loads is
 * dated by that window; in-JVM parallel execution blends windows, so it declines selection.
 */
class InJvmThreadsAttempts : BreakAttempt() {

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

    /** A thread started once, which encodes with Codec when released and keeps the result. */
    private val worker = source(
        "src/test/java/dev/sample/Worker.java",
        """
        package dev.sample;
        import java.util.concurrent.CountDownLatch;
        public final class Worker {
            public static final CountDownLatch release = new CountDownLatch(1);
            public static final CountDownLatch done = new CountDownLatch(1);
            public static volatile String result;
            public static void start() {
                Thread thread = new Thread(() -> {
                    try {
                        release.await();
                        result = new Codec().encode("abc");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
                thread.setDaemon(true);
                thread.start();
            }
            // Bounded: a run that leaves out the test starting the thread must not hang.
            public static void runNow() throws InterruptedException {
                release.countDown();
                done.await(20, java.util.concurrent.TimeUnit.SECONDS);
            }
        }
        """,
    )

    private val starter = source(
        "src/test/java/dev/sample/A1StarterTest.java",
        """
        package dev.sample;
        import org.junit.jupiter.api.Test;
        import static org.junit.jupiter.api.Assertions.assertEquals;
        class A1StarterTest { @Test void starts() { Worker.start(); assertEquals(1, new Other().one()); } }
        """,
    )

    private fun reader(name: String) = source(
        "src/test/java/dev/sample/$name.java",
        """
        package dev.sample;
        import org.junit.jupiter.api.Test;
        import static org.junit.jupiter.api.Assertions.assertEquals;
        class $name { @Test void readsResult() { assertEquals("cba", Worker.result); } }
        """,
    )

    private fun middle(name: String, extra: String = "") = source(
        "src/test/java/dev/sample/$name.java",
        """
        package dev.sample;
        import org.junit.jupiter.api.AfterAll;
        import org.junit.jupiter.api.Test;
        import static org.junit.jupiter.api.Assertions.assertEquals;
        class $name {
            @Test void runs() throws Exception { $extra assertEquals(1, new Other().one()); }
        }
        """,
    )

    private fun changeCodec(dir: File) {
        val file = File(dir, codec.first)
        file.writeText(file.readText().replace("reverse()", "reverse().append(\"!\")"))
    }

    // Track: fixtures. Row: TH1.
    @Test
    fun `a thread that executes the changed class in a later test's window selects the test after it`(
        @TempDir dir: File,
    ) {
        val result = attempt(
            dir, "dev.sample.A3ReaderTest", Aim.Row("shares-jvm-changed-class"),
            listOf(codec, other, first, worker, starter, middle("A2MiddleTest", "Worker.runNow();"),
                reader("A3ReaderTest"), codecTest, classOrderByName),
        ) { changeCodec(it) }

        assertOutcome(Outcome.HELD_BY_RULE, result)
    }

    // Track: fixtures. Row: TH2.
    @Test
    fun `a thread that first loads the changed class while no test runs selects every later test`(
        @TempDir dir: File,
    ) {
        // The thread runs from A2's @AfterAll, so its load falls between A2 and A3, in the window
        // whose coverage selects those two classes; A4 reads the result and is reached by its date.
        val releasing = source(
            "src/test/java/dev/sample/A2MiddleTest.java",
            """
            package dev.sample;
            import org.junit.jupiter.api.AfterAll;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            class A2MiddleTest {
                @Test void runs() { assertEquals(1, new Other().one()); }
                @AfterAll static void release() throws Exception { Worker.runNow(); }
            }
            """,
        )
        val result = attempt(
            dir, "dev.sample.A4ReaderTest", Aim.Row("shares-jvm-changed-class"),
            listOf(codec, other, first, worker, starter, releasing, middle("A3MiddleTest"),
                reader("A4ReaderTest"), codecTest, classOrderByName),
        ) { changeCodec(it) }

        assertOutcome(Outcome.HELD_BY_RULE, result)
    }

    // Track: fixtures. Row: TH3.
    @Test
    fun `in-JVM parallelism enabled in the build script declines selection`(@TempDir dir: File) {
        val result = attempt(
            dir, "dev.sample.A9CodecTest", Aim.Forced("in-jvm-parallelism"),
            listOf(codec, other, first, codecTest, classOrderByName),
        ) {
            File(it, "build.gradle.kts").writeText(
                minimalBuild.replace(
                    "tasks.test { useJUnitPlatform() }",
                    "tasks.test { useJUnitPlatform(); systemProperty(\"junit.jupiter.execution.parallel.enabled\", \"true\") }",
                ),
            )
            changeCodec(it)
        }

        assertOutcome(Outcome.HELD_BY_FORCE, result)
    }

    // Track: code reading. Boundary: the detector reads junit-platform.properties from every
    // directory and jar on the test runtime classpath, not only the test resources. A jar another
    // task of the build produces, as a shared test-configuration module does, carries it here.
    @Test
    fun `parallelism enabled in a jar of the build's own declines selection`(@TempDir dir: File) {
        val confBuild = minimalBuild.replace(
            "tasks.test { useJUnitPlatform() }",
            """
            val confJar = tasks.register<Jar>("confJar") {
                archiveBaseName.set("test-conf")
                from("src/conf")
            }
            dependencies { testRuntimeOnly(files(confJar)) }
            tasks.test { useJUnitPlatform() }
            """.trimIndent(),
        )
        val conf = "src/conf/junit-platform.properties" to "junit.jupiter.execution.parallel.enabled=false\n"

        val result = attempt(
            dir, "dev.sample.A9CodecTest", Aim.Forced("in-jvm-parallelism"),
            listOf(codec, other, first, codecTest, conf),
            buildScript = confBuild,
        ) {
            File(it, conf.first).writeText("junit.jupiter.execution.parallel.enabled=true\n")
            changeCodec(it)
        }

        assertOutcome(Outcome.HELD_BY_FORCE, result)
    }

    // Track: code reading. Boundary: CaptureSession files a class load against the window open
    // when it happens, whatever thread loads it. Here no test owns the thread: a common-pool worker
    // of the JDK looks the changed class up by name without initialising it, so nothing of it runs
    // and coverage never sees it; only the load dates it. A later test reads what the worker found.
    @Test
    fun `a common-pool worker that only looks the changed class up selects the later test that reads its answer`(
        @TempDir dir: File,
    ) {
        val probe = source(
            "src/test/java/dev/sample/PoolProbe.java",
            """
            package dev.sample;
            import java.util.Arrays;
            import java.util.concurrent.CompletableFuture;
            import java.util.concurrent.TimeUnit;
            public final class PoolProbe {
                public static volatile long methods = -1;
                // Bounded: a stuck worker must not hold the build.
                public static void probe() throws Exception {
                    ClassLoader loader = PoolProbe.class.getClassLoader();
                    methods = CompletableFuture.supplyAsync(() -> {
                        try {
                            return Arrays.stream(Class.forName("dev.sample.Codec", false, loader).getDeclaredMethods())
                                    .filter(m -> !m.isSynthetic()).count();
                        } catch (ClassNotFoundException e) {
                            throw new IllegalStateException(e);
                        }
                    }).get(20, TimeUnit.SECONDS);
                }
            }
            """,
        )
        val submitter = middle("A1SubmitterTest", "PoolProbe.probe();")
        val answerReader = source(
            "src/test/java/dev/sample/A2AnswerTest.java",
            """
            package dev.sample;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            class A2AnswerTest { @Test void oneMethod() { assertEquals(1L, PoolProbe.methods); } }
            """,
        )
        val result = attempt(
            dir, "dev.sample.A2AnswerTest", Aim.Row("shares-jvm-changed-class"),
            listOf(codec, other, first, probe, submitter, answerReader, codecTest, classOrderByName),
        ) {
            val file = File(it, codec.first)
            file.writeText(file.readText().replace("public String encode", "public int size() { return 0; } public String encode"))
        }

        assertOutcome(Outcome.HELD_BY_RULE, result)
    }
}
