package io.github.zeuspizza.yoriwake.gradle.breaking

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Attempts on state a test JVM builds once and every later test shares. Coverage credits such
 * work to whichever test or window triggered it, so a changed class selects every test that ran at
 * or after it first arrived in the JVM; startup work no test owns forces a full run, and a map that
 * cannot be read runs everything.
 */
class OncePerJvmStateAttempts : BreakAttempt() {

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

    /** A test that asserts on Codec's bytes, read from the copy, without loading or executing Codec. */
    private fun copyReader(name: String) = source(
        "src/test/java/dev/sample/$name.java",
        """
        package dev.sample;
        import java.nio.charset.StandardCharsets;
        import java.nio.file.Files;
        import java.nio.file.Paths;
        import org.junit.jupiter.api.Test;
        import static org.junit.jupiter.api.Assertions.assertFalse;
        class $name {
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

    // Track: code reading. Boundary: MapReader reads a missing coverage file as "no map", which runs
    // everything. A run declined over in-JVM parallelism ran every test and still leaves no map, so
    // the next selecting run, with the parallelism gone, must not read the absence as a map that
    // covers nothing.
    @Test
    fun `a map a declined run never wrote runs everything once the decline is gone`(@TempDir dir: File) {
        val parallel = "src/test/resources/junit-platform.properties" to
            "junit.jupiter.execution.parallel.enabled=true\n"

        val result = attempt(
            dir, "dev.sample.A9CodecTest", Aim.Forced("map-unusable"),
            listOf(codec, other, first, codecTest, parallel),
        ) {
            File(it, parallel.first).delete()
            changeCodec(it)
        }

        assertOutcome(BreakAttempt.Outcome.HELD_BY_FORCE, result)
    }

    // Track: code reading. Boundary: what a class's first load in the window between two test
    // classes is filed under. The take that opens the next class's first test drains it under the
    // class-scoped record of the two classes on either side, whose position precedes every later
    // test; the window's coverage selects only those two classes, so a third class that reads the
    // state the window built is reached by the JVM-wide rule alone.
    @Test
    fun `state an @AfterAll built between two classes selects a later class that reads it`(@TempDir dir: File) {
        val cache = source(
            "src/test/java/dev/sample/Cache.java",
            "package dev.sample;\npublic final class Cache { public static String value; }",
        )
        val builder = source(
            "src/test/java/dev/sample/A1BuilderTest.java",
            """
            package dev.sample;
            import org.junit.jupiter.api.AfterAll;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            class A1BuilderTest {
                @Test void runs() { assertEquals(1, new Other().one()); }
                @AfterAll static void build() { Cache.value = new Codec().encode("abc"); }
            }
            """,
        )
        val middle = source(
            "src/test/java/dev/sample/A2MiddleTest.java",
            """
            package dev.sample;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            class A2MiddleTest { @Test void runs() { assertEquals(1, new Other().one()); } }
            """,
        )
        val target = source(
            "src/test/java/dev/sample/A3CachedTest.java",
            """
            package dev.sample;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            class A3CachedTest { @Test void readsCache() { assertEquals("cba", Cache.value); } }
            """,
        )

        val result = attempt(
            dir, "dev.sample.A3CachedTest", Aim.Row("shares-jvm-changed-class"),
            listOf(codec, other, first, cache, builder, middle, target, codecTest, classOrderByName),
        ) { changeCodec(it) }

        assertOutcome(BreakAttempt.Outcome.HELD_BY_RULE, result)
    }

    // Track: code reading. Boundary: a jar read with code other than the JDK's zip is recorded by
    // path, and the decoder lists its entries only when the run is over. A jar the test deleted by
    // then cannot be listed, which must count as every class touched from that test, not none.
    @Test
    fun `a jar read and deleted before the decode selects every later test in its JVM`(@TempDir dir: File) {
        val scanner = source(
            "src/test/java/dev/sample/A1JarScanTest.java",
            """
            package dev.sample;
            import java.io.File;
            import java.io.FileInputStream;
            import java.io.FileOutputStream;
            import java.util.zip.ZipEntry;
            import java.util.zip.ZipInputStream;
            import java.util.zip.ZipOutputStream;
            import org.junit.jupiter.api.Test;
            import org.junit.jupiter.api.io.TempDir;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            class A1JarScanTest {
                @TempDir File temp;
                @Test void scans() throws Exception {
                    File jar = new File(temp, "plugin.jar");
                    try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(jar))) {
                        out.putNextEntry(new ZipEntry("dev/sample/Plugin.class"));
                        out.write(new byte[] {1, 2, 3});
                        out.closeEntry();
                    }
                    try (ZipInputStream in = new ZipInputStream(new FileInputStream(jar))) {
                        assertEquals("dev/sample/Plugin.class", in.getNextEntry().getName());
                    }
                }
            }
            """,
        )

        val result = attempt(
            dir, "dev.sample.A2ReaderTest", Aim.Row("shares-jvm-unobserved"),
            listOf(codec, other, first, scanner, copyReader("A2ReaderTest"), codecTest, classOrderByName),
            buildScript = copiedCodecBuild,
        ) { changeCodec(it) }

        assertOutcome(BreakAttempt.Outcome.HELD_BY_RULE, result)
    }

    // Track: code reading. Boundary: the startup window ends at the Platform's plan start, and
    // everything before it belongs to no test. A launcher session listener runs before discovery,
    // so state it builds from a class is startup coverage, which no test owns.
    @Test
    fun `state a launcher session listener built from a changed class forces a full run`(@TempDir dir: File) {
        val sessionBuild = minimalBuild.replace(
            "testRuntimeOnly(\"org.junit.platform:junit-platform-launcher\")",
            "testImplementation(\"org.junit.platform:junit-platform-launcher\")",
        )
        val listener = source(
            "src/test/java/dev/sample/CodecSession.java",
            """
            package dev.sample;
            import org.junit.platform.launcher.LauncherSession;
            import org.junit.platform.launcher.LauncherSessionListener;
            public class CodecSession implements LauncherSessionListener {
                @Override public void launcherSessionOpened(LauncherSession session) {
                    System.setProperty("codec.abc", new Codec().encode("abc"));
                }
            }
            """,
        )
        val registration = "src/test/resources/META-INF/services/org.junit.platform.launcher.LauncherSessionListener" to
            "dev.sample.CodecSession\n"
        val target = source(
            "src/test/java/dev/sample/A1SessionTest.java",
            """
            package dev.sample;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            class A1SessionTest { @Test void readsSession() { assertEquals("cba", System.getProperty("codec.abc")); } }
            """,
        )

        val result = attempt(
            dir, "dev.sample.A1SessionTest", Aim.Forced("startup-coverage"),
            listOf(codec, other, first, listener, registration, target, classOrderByName),
            buildScript = sessionBuild,
        ) { changeCodec(it) }

        assertOutcome(BreakAttempt.Outcome.HELD_BY_FORCE, result)
    }
}
