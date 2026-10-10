package io.github.zeuspizza.yoriwake.gradle.breaking

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Attempts on reading a class's file rather than running the class. A read of a class file dates
 * the test by that class, wherever the read is made from, unless it is the JDK reading the file to
 * define the class, which the class's load already records.
 */
class ResourcesAndFilesAttempts : BreakAttempt() {

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

    // Track: code reading. Boundary: the read happens under the JDK's defineClass frame, because
    // the JDK calls the loader's getPermissions while it defines Other. The loader is the test's
    // own code, standing between the read and the definition, so the read is the test's.
    @Test
    fun `a class file read from a loader's getPermissions is held by the read`(@TempDir dir: File) {
        val reader = source(
            "src/test/java/dev/sample/A1PermissionsReaderTest.java",
            """
            package dev.sample;
            import java.io.FileInputStream;
            import java.io.IOException;
            import java.io.UncheckedIOException;
            import java.net.URL;
            import java.net.URLClassLoader;
            import java.nio.charset.StandardCharsets;
            import java.nio.file.Path;
            import java.nio.file.Paths;
            import java.security.CodeSource;
            import java.security.PermissionCollection;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertFalse;
            class A1PermissionsReaderTest {
                static String bytes;
                @Test void noAppend() throws Exception {
                    URL here = Other.class.getProtectionDomain().getCodeSource().getLocation();
                    Path codecFile = Paths.get(here.toURI()).resolve("dev/sample/Codec.class");
                    try (URLClassLoader loader = new URLClassLoader(new URL[] {here}, null) {
                        @Override protected PermissionCollection getPermissions(CodeSource cs) {
                            try (FileInputStream in = new FileInputStream(codecFile.toFile())) {
                                bytes = new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
                            } catch (IOException e) {
                                throw new UncheckedIOException(e);
                            }
                            return super.getPermissions(cs);
                        }
                    }) {
                        loader.loadClass("dev.sample.Other");
                    }
                    assertFalse(bytes.contains("append"));
                }
            }
            """,
        )
        val result = attempt(
            dir, "dev.sample.A1PermissionsReaderTest", Aim.Row("shares-jvm-changed-class"),
            listOf(codec, other, first, reader, codecTest, classOrderByName),
        ) { changeCodec(it) }

        assertOutcome(BreakAttempt.Outcome.HELD_BY_RULE, result)
    }
}
