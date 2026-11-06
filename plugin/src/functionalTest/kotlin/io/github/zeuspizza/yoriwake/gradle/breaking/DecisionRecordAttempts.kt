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

        assertOutcome(Outcome.HELD_BY_RULE, result)
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

        assertOutcome(Outcome.HELD_BY_RULE, result)
    }

    // Track: fixtures. Row: DR6.
    @Test
    fun `a test whose descriptor throws while selection reads it runs as selection failed`(@TempDir dir: File) {
        // An engine on the test classpath whose test reads its tags lazily and fails on the second
        // read: the Platform reads them once to build its plan, and selection reads them twice, for
        // pins and for declared dependencies. Its test reads a copy of Codec's source, so its own
        // record does not reach the change and nothing but the failed selection keeps it.
        val engine = source(
            "src/test/java/dev/sample/SampleEngine.java",
            """
            package dev.sample;
            import java.nio.file.Files;
            import java.nio.file.Paths;
            import java.util.Collections;
            import java.util.Set;
            import java.util.concurrent.atomic.AtomicInteger;
            import org.junit.platform.engine.EngineDiscoveryRequest;
            import org.junit.platform.engine.EngineExecutionListener;
            import org.junit.platform.engine.ExecutionRequest;
            import org.junit.platform.engine.TestDescriptor;
            import org.junit.platform.engine.TestEngine;
            import org.junit.platform.engine.TestExecutionResult;
            import org.junit.platform.engine.TestTag;
            import org.junit.platform.engine.UniqueId;
            import org.junit.platform.engine.discovery.ClassSelector;
            import org.junit.platform.engine.support.descriptor.AbstractTestDescriptor;
            import org.junit.platform.engine.support.descriptor.ClassSource;
            import org.junit.platform.engine.support.descriptor.EngineDescriptor;
            import org.junit.platform.engine.support.descriptor.MethodSource;
            public class SampleEngine implements TestEngine {
                @Override public String getId() { return "sample"; }
                @Override public TestDescriptor discover(EngineDiscoveryRequest request, UniqueId root) {
                    EngineDescriptor engine = new EngineDescriptor(root, "sample");
                    for (ClassSelector selector : request.getSelectorsByType(ClassSelector.class)) {
                        if (selector.getClassName().equals("dev.sample.A1EngineTest")) {
                            UniqueId type = root.append("class", "dev.sample.A1EngineTest");
                            TestDescriptor container = new ClassContainer(type);
                            container.addChild(new Check(type.append("method", "readsTheCopy()")));
                            engine.addChild(container);
                        }
                    }
                    return engine;
                }
                @Override public void execute(ExecutionRequest request) {
                    EngineExecutionListener listener = request.getEngineExecutionListener();
                    TestDescriptor engine = request.getRootTestDescriptor();
                    listener.executionStarted(engine);
                    for (TestDescriptor container : engine.getChildren()) {
                        listener.executionStarted(container);
                        for (TestDescriptor test : container.getChildren()) {
                            listener.executionStarted(test);
                            TestExecutionResult result;
                            try {
                                String copy = Files.readString(Paths.get(System.getProperty("codec.copy")));
                                result = copy.contains("reverse().toString()") ? TestExecutionResult.successful()
                                    : TestExecutionResult.failed(new AssertionError("the copy changed"));
                            } catch (Exception failure) {
                                result = TestExecutionResult.failed(failure);
                            }
                            listener.executionFinished(test, result);
                        }
                        listener.executionFinished(container, TestExecutionResult.successful());
                    }
                    listener.executionFinished(engine, TestExecutionResult.successful());
                }
                static final class ClassContainer extends AbstractTestDescriptor {
                    ClassContainer(UniqueId id) { super(id, "A1EngineTest", ClassSource.from("dev.sample.A1EngineTest")); }
                    @Override public Type getType() { return Type.CONTAINER; }
                }
                static final class Check extends AbstractTestDescriptor {
                    private final AtomicInteger reads = new AtomicInteger();
                    Check(UniqueId id) { super(id, "readsTheCopy()", MethodSource.from("dev.sample.A1EngineTest", "readsTheCopy")); }
                    @Override public Type getType() { return Type.TEST; }
                    @Override public Set<TestTag> getTags() {
                        if (reads.incrementAndGet() == 2) throw new IllegalStateException("tags not ready");
                        return Collections.emptySet();
                    }
                }
            }
            """,
        )
        val service = "src/test/resources/META-INF/services/org.junit.platform.engine.TestEngine" to "dev.sample.SampleEngine\n"
        val target = source("src/test/java/dev/sample/A1EngineTest.java", "package dev.sample;\npublic class A1EngineTest {}")
        val build = minimalBuild.replace(
            "testRuntimeOnly(\"org.junit.platform:junit-platform-launcher\")",
            "testRuntimeOnly(\"org.junit.platform:junit-platform-launcher\")\n" +
                "testImplementation(\"org.junit.platform:junit-platform-engine\")",
        ).replace(
            "tasks.test { useJUnitPlatform() }",
            """
            val copyCodec = tasks.register<Copy>("copyCodec") {
                from("src/main/java/dev/sample/Codec.java")
                rename { "codec.txt" }
                into(layout.buildDirectory.dir("copies"))
            }
            tasks.test {
                useJUnitPlatform()
                forkEvery = 1
                dependsOn(copyCodec)
                systemProperty("codec.copy", layout.buildDirectory.file("copies/codec.txt").get().asFile.absolutePath)
            }
            """.trimIndent(),
        )
        val result = attempt(
            dir, "dev.sample.A1EngineTest", Aim.Row("SELECTION_FAILED"),
            listOf(codec, other, unrelated("A0UnrelatedTest"), engine, service, target, codecTest),
            buildScript = build,
        ) { changeCodec(it) }

        assertOutcome(Outcome.HELD_BY_RULE, result)
    }
}
