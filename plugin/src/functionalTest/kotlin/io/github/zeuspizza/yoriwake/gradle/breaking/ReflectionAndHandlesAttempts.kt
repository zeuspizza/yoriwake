package io.github.zeuspizza.yoriwake.gradle.breaking

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Attempts on who counts as the caller of a hooked call. A hidden class definition or a use of
 * the foreign-function API is the JDK's own, and not recorded, only when the code that asked for
 * it is the JDK's; reflection, method handles and proxies call on someone else's behalf, so the
 * walk passes over them to the class that made the call.
 */
class ReflectionAndHandlesAttempts : BreakAttempt() {

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

    private fun changeCodec(dir: File) {
        val file = File(dir, codec.first)
        file.writeText(file.readText().replace("reverse()", "reverse().append(\"!\")"))
    }

    /** A test that defines a hidden copy of Codec through [define], which leaves its lookup in `hidden`, and runs it. */
    private fun hiddenCopyTest(define: String) = source(
        "src/test/java/dev/sample/A1HiddenCopyTest.java",
        """
        package dev.sample;
        import java.lang.invoke.MethodHandle;
        import java.lang.invoke.MethodHandleProxies;
        import java.lang.invoke.MethodHandles;
        import java.lang.invoke.MethodType;
        import java.nio.file.Files;
        import java.nio.file.Paths;
        import java.util.function.Function;
        import org.junit.jupiter.api.Test;
        import static org.junit.jupiter.api.Assertions.assertEquals;
        class A1HiddenCopyTest {
            @Test void copiesCodec() throws Throwable {
                byte[] bytes = Files.readAllBytes(Paths.get(System.getProperty("codec.copy")));
                MethodHandle handle = MethodHandles.insertArguments(MethodHandles.lookup().findVirtual(
                        MethodHandles.Lookup.class, "defineHiddenClass", MethodType.methodType(MethodHandles.Lookup.class,
                                byte[].class, boolean.class, MethodHandles.Lookup.ClassOption[].class))
                        .bindTo(MethodHandles.lookup()), 1, true, new MethodHandles.Lookup.ClassOption[0])
                        .asType(MethodType.methodType(Object.class, Object.class));
                @SuppressWarnings("unchecked")
                Function<Object, Object> definer = MethodHandleProxies.asInterfaceInstance(Function.class, handle);
                MethodHandles.Lookup hidden = $define;
                Class<?> copy = hidden.lookupClass();
                assertEquals("cba", copy.getMethod("encode", String.class).invoke(copy.getConstructor().newInstance(), "abc"));
            }
        }
        """,
    )

    // Track: code reading. Boundary: an interface proxy only dispatches to its handler, and the
    // handler only invokes a method handle, so the walk passes over both to the test that called
    // the proxy. The proxy's own class and the handler's are both in packages the walk passes over.
    @Test
    fun `a hidden copy defined through an interface proxy the test calls itself is held by its definition`(
        @TempDir dir: File,
    ) {
        val result = attempt(
            dir, "dev.sample.A1HiddenCopyTest", Aim.Row("shares-jvm-changed-class"),
            listOf(codec, other, first, hiddenCopyTest("(MethodHandles.Lookup) definer.apply(bytes)"), codecTest, classOrderByName),
            buildScript = copiedCodecBuild,
        ) { changeCodec(it) }

        assertOutcome(BreakAttempt.Outcome.HELD_BY_RULE, result)
    }

    // Track: code reading. Boundary: past a proxy, the first judged frame is whoever called the
    // proxy, here the JDK's stream code. A proxy dispatches to a handle someone else chose, so JDK
    // code beyond it did not ask for the definition and must not make it the JDK's own.
    @Test
    fun `a hidden copy defined through an interface proxy that JDK code calls is held by its definition`(
        @TempDir dir: File,
    ) {
        val result = attempt(
            dir, "dev.sample.A1HiddenCopyTest", Aim.Row("shares-jvm-changed-class"),
            listOf(codec, other, first,
                hiddenCopyTest("(MethodHandles.Lookup) java.util.stream.Stream.of((Object) bytes).map(definer).findFirst().get()"),
                codecTest, classOrderByName),
            buildScript = copiedCodecBuild,
        ) { changeCodec(it) }

        assertOutcome(BreakAttempt.Outcome.HELD_BY_RULE, result)
    }

    // Track: code reading. Boundary: the frame above a hooked call is its caller, and a method
    // reference's own class is hidden, so without hidden frames the stream that calls it would be
    // taken for the caller. The test JVM is Java 21, where the foreign-function API is a preview.
    @Test
    fun `the foreign-function API reached through a method reference JDK code calls is held by the JVM-wide rule`(
        @TempDir dir: File,
    ) {
        val previewBuild = copiedCodecBuild.replace(
            "useJUnitPlatform()\n",
            "useJUnitPlatform()\n    jvmArgs(\"--enable-preview\")\n",
        ) + "\ntasks.withType<JavaCompile>().configureEach { options.compilerArgs.add(\"--enable-preview\"); options.release = 21 }\n"
        val ffi = source(
            "src/test/java/dev/sample/A1NativeTest.java",
            """
            package dev.sample;
            import java.lang.foreign.Linker;
            import java.util.function.Supplier;
            import java.util.stream.Stream;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertNotNull;
            class A1NativeTest {
                @Test void links() {
                    Supplier<Linker> linker = Linker::nativeLinker;
                    assertNotNull(Stream.generate(linker).limit(1).findFirst().get());
                }
            }
            """,
        )
        val reader = source(
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
        val result = attempt(
            dir, "dev.sample.A2ReaderTest", Aim.Row("shares-jvm-unobserved"),
            listOf(codec, other, first, ffi, reader, codecTest, classOrderByName),
            buildScript = previewBuild,
        ) { changeCodec(it) }

        assertOutcome(BreakAttempt.Outcome.HELD_BY_RULE, result)
    }
}
