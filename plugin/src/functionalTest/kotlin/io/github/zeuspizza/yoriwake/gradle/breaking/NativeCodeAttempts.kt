package io.github.zeuspizza.yoriwake.gradle.breaking

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Attempts on native libraries a test loads. Native code can read any class file out of the agent's
 * sight, so a library loaded from outside the JDK makes every later test in its JVM run after any
 * change, unless it is a reviewed library loaded by its own class.
 */
class NativeCodeAttempts : BreakAttempt() {

    private fun source(path: String, body: String) = path to body.trimIndent()

    private val codec = source(
        "src/main/java/dev/sample/Codec.java",
        "package dev.sample;\npublic class Codec { public String encode(String s) { return new StringBuilder(s).reverse().toString(); } }",
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

    /** Executes Codec, so the change to it is covered and does not force a full run. */
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

    /** Reads Codec's bytes as data and fails once the change adds "append" to them: never executes Codec. */
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

    private fun changeCodec(dir: File) {
        val file = File(dir, codec.first)
        file.writeText(file.readText().replace("reverse()", "reverse().append(\"!\")"))
    }

    /**
     * A test that has [loader] load a copy of the JDK's own zip library under snappy-java's file
     * name: a load that succeeds, since only a library the JDK opened is judged as loaded.
     */
    private fun copyLoadingTest(name: String, loader: String) = source(
        "src/test/java/dev/sample/$name.java",
        """
        package dev.sample;
        import java.nio.file.Files;
        import java.nio.file.Path;
        import org.junit.jupiter.api.Test;
        class $name {
            @Test void loads() throws Exception {
                Path home = Path.of(System.getProperty("java.home"));
                Path zip = home.resolve("lib").resolve(System.mapLibraryName("zip"));
                if (!Files.exists(zip)) zip = home.resolve("bin").resolve(System.mapLibraryName("zip"));
                Path copy = Files.createTempDirectory("lib").resolve(System.mapLibraryName("snappyjava"));
                Files.copy(zip, copy);
                $loader.load(copy.toString());
            }
        }
        """,
    )

    private fun loaderClass(simpleName: String) = source(
        "src/test/java/org/xerial/snappy/$simpleName.java",
        """
        package org.xerial.snappy;
        public class $simpleName { public static void load(String file) { System.load(file); } }
        """,
    )

    // Track: code reading. Boundary: ReviewedLibraries.reviewed matches the loading class by its
    // owner's exact name or a name nested in it (owner + "$"). A class whose name only starts with
    // the owner's, loading the reviewed file name, stands just past that check: it is not the owner,
    // so its load touches every class from that point.
    @Test
    fun `a reviewed library's file loaded by a class named like its owner makes its JVM unobserved from that point`(@TempDir dir: File) {
        val result = attempt(
            dir, "dev.sample.A2ReaderTest", Aim.Row("shares-jvm-unobserved"),
            listOf(
                codec, other, first, loaderClass("SnappyLoaderShim"),
                copyLoadingTest("A1LoaderTest", "org.xerial.snappy.SnappyLoaderShim"), reader, codecTest, classOrderByName,
            ),
            buildScript = copiedCodecBuild,
        ) { changeCodec(it) }

        assertOutcome(Outcome.HELD_BY_RULE, result)
    }

    // Track: code reading. Boundary: a reviewed library loaded by its own class records only a
    // lookup of every class, which Selector.sharesJvm reads for an edited test class alone, so it
    // opens no window for a changed main class. The test before the changed class's first touch
    // stays out, and the changed-class window from the target's read of Codec's class file still
    // holds the target.
    @Test
    fun `a reviewed library loaded by its own class leaves the changed-class window as it was`(@TempDir dir: File) {
        val classFileReader = source(
            "src/test/java/dev/sample/A2ClassFileReaderTest.java",
            """
            package dev.sample;
            import java.io.InputStream;
            import java.nio.charset.StandardCharsets;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertFalse;
            class A2ClassFileReaderTest {
                @Test void noAppend() throws Exception {
                    try (InputStream in = getClass().getClassLoader().getResourceAsStream("dev/sample/Codec.class")) {
                        assertFalse(new String(in.readAllBytes(), StandardCharsets.ISO_8859_1).contains("append"));
                    }
                }
            }
            """,
        )
        val result = attempt(
            dir, "dev.sample.A2ClassFileReaderTest", Aim.Row("shares-jvm-changed-class"),
            listOf(
                codec, other, first, loaderClass("SnappyLoader"),
                copyLoadingTest("A1LoaderTest", "org.xerial.snappy.SnappyLoader"), classFileReader, codecTest, classOrderByName,
            ),
        ) { changeCodec(it) }

        assertOutcome(Outcome.HELD_BY_RULE, result)
    }
}
