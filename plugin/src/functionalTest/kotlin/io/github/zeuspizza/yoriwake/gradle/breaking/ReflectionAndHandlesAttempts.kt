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

    /** The copied-bytes reader, used as the JVM-wide target after an unobserving touch. */
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

    // Track: code reading. Boundary: calledFromJdk judges the first non-passed-over frame above the
    // hooked call. A java.lang.reflect.Proxy's InvocationHandler runs the hooked call itself, and
    // the handler's own class is reached before the proxy frame (and before throughAProxy is set),
    // so a handler the test wrote is judged the caller and the definition is recorded. This is RH11.
    @Test
    fun `a hidden copy defined by a reflect Proxy's own handler the test calls is held by its definition`(
        @TempDir dir: File,
    ) {
        val test = source(
            "src/test/java/dev/sample/A1HiddenCopyTest.java",
            """
            package dev.sample;
            import java.lang.invoke.MethodHandles;
            import java.lang.reflect.InvocationHandler;
            import java.lang.reflect.Method;
            import java.lang.reflect.Proxy;
            import java.nio.file.Files;
            import java.nio.file.Paths;
            import java.util.function.Function;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            class A1HiddenCopyTest {
                static final class Definer implements InvocationHandler {
                    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                        return MethodHandles.lookup().defineHiddenClass((byte[]) args[0], true);
                    }
                }
                @Test void copiesCodec() throws Throwable {
                    byte[] bytes = Files.readAllBytes(Paths.get(System.getProperty("codec.copy")));
                    @SuppressWarnings("unchecked")
                    Function<byte[], Object> definer = (Function<byte[], Object>) Proxy.newProxyInstance(
                        getClass().getClassLoader(), new Class<?>[]{Function.class}, new Definer());
                    MethodHandles.Lookup hidden = (MethodHandles.Lookup) definer.apply(bytes);
                    Class<?> copy = hidden.lookupClass();
                    assertEquals("cba", copy.getMethod("encode", String.class)
                        .invoke(copy.getConstructor().newInstance(), "abc"));
                }
            }
            """,
        )
        val result = attempt(
            dir, "dev.sample.A1HiddenCopyTest", Aim.Row("shares-jvm-changed-class"),
            listOf(codec, other, first, test, codecTest, classOrderByName),
            buildScript = copiedCodecBuild,
        ) { changeCodec(it) }

        assertOutcome(BreakAttempt.Outcome.HELD_BY_RULE, result)
    }

    // Track: fixtures. Row: RH10. An interface proxy over a hooked call run by JDK code on another
    // thread (CompletableFuture.supplyAsync): past the proxy on that thread stands the executor's
    // JDK frames, so throughAProxy makes the definition the project's, not the JDK's.
    @Test
    fun `a hidden copy defined through a proxy JDK runs on another thread is held by its definition`(
        @TempDir dir: File,
    ) {
        val result = attempt(
            dir, "dev.sample.A1HiddenCopyTest", Aim.Row("shares-jvm-changed-class"),
            listOf(
                codec, other, first,
                hiddenCopyTest(
                    "(MethodHandles.Lookup) java.util.concurrent.CompletableFuture" +
                        ".supplyAsync(() -> definer.apply(bytes)).join()",
                ),
                codecTest, classOrderByName,
            ),
            buildScript = copiedCodecBuild,
        ) { changeCodec(it) }

        assertOutcome(BreakAttempt.Outcome.HELD_BY_RULE, result)
    }

    // Track: fixtures. Row: RH12. A java.beans.Expression naming nativeLinker: the call runs through
    // MethodUtil's Trampoline, which sun.reflect.misc names but whose own loader is not the JDK's, so
    // the walk must not take the JDK for the caller. (Statement refuses anything in java.lang.invoke,
    // so defineHiddenClass cannot be named this way.) Java 21: the API is a preview.
    @Test
    fun `the foreign-function API named by a java beans Expression is held by the JVM-wide rule`(
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
            import java.beans.Expression;
            import java.lang.foreign.Linker;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertNotNull;
            class A1NativeTest {
                @Test void links() throws Exception {
                    assertNotNull(new Expression(Linker.class, "nativeLinker", new Object[0]).getValue());
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

    // Track: fixtures. Row: RH13. A java.beans.EventHandler proxy whose action is a method the test
    // wrote: that method makes the hooked call, so it is the frame judged, above the EventHandler,
    // its Trampoline and the proxy.
    @Test
    fun `a hidden copy defined through a method a java beans EventHandler proxy invokes is held by its definition`(
        @TempDir dir: File,
    ) {
        val test = source(
            "src/test/java/dev/sample/A1HiddenCopyTest.java",
            """
            package dev.sample;
            import java.beans.EventHandler;
            import java.lang.invoke.MethodHandles;
            import java.nio.file.Files;
            import java.nio.file.Paths;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            class A1HiddenCopyTest {
                public static final class Trigger {
                    byte[] bytes;
                    MethodHandles.Lookup result;
                    public void go() {
                        try { result = MethodHandles.lookup().defineHiddenClass(bytes, true); }
                        catch (Throwable t) { throw new RuntimeException(t); }
                    }
                }
                @Test void copiesCodec() throws Throwable {
                    Trigger trigger = new Trigger();
                    trigger.bytes = Files.readAllBytes(Paths.get(System.getProperty("codec.copy")));
                    Runnable proxy = EventHandler.create(Runnable.class, trigger, "go");
                    proxy.run();
                    Class<?> copy = trigger.result.lookupClass();
                    assertEquals("cba", copy.getMethod("encode", String.class)
                        .invoke(copy.getConstructor().newInstance(), "abc"));
                }
            }
            """,
        )
        val result = attempt(
            dir, "dev.sample.A1HiddenCopyTest", Aim.Row("shares-jvm-changed-class"),
            listOf(codec, other, first, test, codecTest, classOrderByName),
            buildScript = copiedCodecBuild,
        ) { changeCodec(it) }

        assertOutcome(BreakAttempt.Outcome.HELD_BY_RULE, result)
    }

    // Track: code reading. Boundary: nativeCode() judges its caller with the same proxy-aware walk
    // as a definition. A MethodHandleProxies instance JDK code (Stream.map) calls to reach
    // Linker.nativeLinker is passed over as a proxy, so the JDK beyond it is not the caller and the
    // foreign-function touch is recorded, making its JVM unobserved. Java 21: the API is a preview.
    @Test
    fun `the foreign-function API reached through a proxy JDK code calls is held by the JVM-wide rule`(
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
            import java.lang.invoke.MethodHandle;
            import java.lang.invoke.MethodHandleProxies;
            import java.lang.invoke.MethodHandles;
            import java.lang.invoke.MethodType;
            import java.util.function.Function;
            import java.util.stream.Stream;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertNotNull;
            class A1NativeTest {
                @Test void links() throws Throwable {
                    MethodHandle handle = MethodHandles.dropArguments(MethodHandles.lookup()
                        .findStatic(Linker.class, "nativeLinker", MethodType.methodType(Linker.class)), 0, Object.class)
                        .asType(MethodType.methodType(Object.class, Object.class));
                    @SuppressWarnings("unchecked")
                    Function<Object, Object> link = MethodHandleProxies.asInterfaceInstance(Function.class, handle);
                    assertNotNull(Stream.of((Object) "x").map(link).findFirst().get());
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
