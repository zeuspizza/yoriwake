package io.github.zeuspizza.yoriwake.gradle.breaking

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Attempts on code and files that reach a test from another module of the same build: a sibling's
 * jar on the classpath, what the sibling stamps into it, and a sibling's files a test reads by path
 * although the sibling is not on its classpath at all.
 */
class OtherModulesJarsAttempts : BreakAttempt() {

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

    private fun settings(module: String) = "settings.gradle.kts" to "rootProject.name = \"sample\"\ninclude(\"$module\")\n"

    /** [minimalBuild] with [dependencies] added and [extra] inside the test task's block. */
    private fun rootBuild(dependencies: String = "", extra: String = "") = minimalBuild
        .replace("testRuntimeOnly(\"org.junit.platform:junit-platform-launcher\")", "testRuntimeOnly(\"org.junit.platform:junit-platform-launcher\")\n    $dependencies")
        .replace("tasks.test { useJUnitPlatform() }", "tasks.test {\n    useJUnitPlatform()\n    $extra\n}")

    // Track: code reading. Boundary: a sibling's jar is the build's own output, so each of its
    // entries is digested by content, never the jar whole: a jar keeps entry timestamps. The sibling
    // stamps `git describe --dirty` into its manifest, which an uncommitted edit anywhere moves.
    @Test
    fun `a sibling jar whose manifest stamps git describe runs the test that reads it`(@TempDir dir: File) {
        val lib = source(
            "lib/build.gradle.kts",
            """
            plugins { `java-library` }
            tasks.jar {
                val describe = providers.exec { commandLine("git", "describe", "--always", "--dirty") }
                    .standardOutput.asText.map { it.trim() }
                inputs.property("describe", describe)
                manifest { attributes("Implementation-Version" to describe.get()) }
            }
            """,
        )
        val libClass = source("lib/src/main/java/dev/lib/Lib.java", "package dev.lib;\npublic class Lib {}")
        val revisionTest = source(
            "src/test/java/dev/sample/A1RevisionTest.java",
            """
            package dev.sample;
            import java.io.InputStream;
            import java.net.URL;
            import java.util.Enumeration;
            import java.util.jar.Manifest;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertFalse;
            import static org.junit.jupiter.api.Assertions.assertNotNull;
            class A1RevisionTest {
                @Test void builtFromACleanTree() throws Exception {
                    String version = null;
                    Enumeration<URL> manifests = getClass().getClassLoader().getResources("META-INF/MANIFEST.MF");
                    // The sibling's own manifest: the test libraries' jars carry versions of their own.
                    while (manifests.hasMoreElements()) {
                        URL manifest = manifests.nextElement();
                        if (!manifest.toString().contains("/lib/build/libs/")) continue;
                        try (InputStream in = manifest.openStream()) {
                            version = new Manifest(in).getMainAttributes().getValue("Implementation-Version");
                        }
                    }
                    assertNotNull(version);
                    assertFalse(version.endsWith("-dirty"), version);
                }
            }
            """,
        )
        val result = attempt(
            dir, "dev.sample.A1RevisionTest", Aim.Forced("classpath-files-changed"),
            listOf(settings("lib"), lib, libClass, codec, other, first, revisionTest, codecTest, classOrderByName),
            buildScript = rootBuild(dependencies = "testImplementation(project(\":lib\"))"),
        ) { changeCodec(it) }

        assertOutcome(Outcome.HELD_BY_FORCE, result)
    }

    // Track: code reading. Boundary: a sibling counts as on this task's classpath when a declared
    // project dependency names it, or when a classpath entry lies under its build directory. A file
    // dependency on the sibling's jar declares nothing, and the jar is under its build directory.
    @Test
    fun `a change in a sibling reached through a file dependency on its jar is held by its reach`(@TempDir dir: File) {
        val lib = source("lib/build.gradle.kts", "plugins { java }")
        val thing = source(
            "lib/src/main/java/dev/lib/Thing.java",
            "package dev.lib;\npublic class Thing { public int n() { return 1; } }",
        )
        val thingTest = source(
            "src/test/java/dev/sample/A1ThingTest.java",
            """
            package dev.sample;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            class A1ThingTest { @Test void one() { assertEquals(1, new dev.lib.Thing().n()); } }
            """,
        )
        val result = attempt(
            dir, "dev.sample.A1ThingTest", Aim.Row("reaches-change"),
            listOf(settings("lib"), lib, thing, codec, other, first, thingTest, classOrderByName),
            buildScript = rootBuild(dependencies = "testImplementation(files(\"lib/build/libs/lib.jar\").builtBy(\":lib:jar\"))"),
        ) {
            File(it, thing.first).writeText("package dev.lib;\npublic class Thing { public int n() { return 2; } }")
        }

        assertOutcome(Outcome.HELD_BY_RULE, result)
    }

    // Track: code reading. Boundary: a source or resource file of a module that is not on this
    // task's classpath leaves the change set unless a compiled class names it, or a directory above
    // it, since a test can only read a file it can name. The build script hands the test the
    // sibling's file through a system property, so no class names it.
    @Test
    fun `a sibling's file the build script hands a test by path runs that test when it changes`(@TempDir dir: File) {
        val golden = source("golden/build.gradle.kts", "")
        val answer = source("golden/src/main/resources/answer.txt", "42")
        val siblingTest = source(
            "src/test/java/dev/sample/A1SiblingTest.java",
            """
            package dev.sample;
            import java.nio.file.Files;
            import java.nio.file.Paths;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            class A1SiblingTest {
                @Test void answer() throws Exception {
                    assertEquals("42", Files.readString(Paths.get(System.getProperty("sibling.path"))).trim());
                }
            }
            """,
        )
        val result = attempt(
            dir, "dev.sample.A1SiblingTest", Aim.Forced("unmappable-paths"),
            listOf(settings("golden"), golden, answer, codec, other, first, siblingTest, codecTest, classOrderByName),
            buildScript = rootBuild(
                extra = "systemProperty(\"sibling.path\", file(\"golden/src/main/resources/answer.txt\").absolutePath)",
            ),
        ) {
            changeCodec(it)
            File(it, answer.first).writeText("43")
        }

        assertOutcome(Outcome.HELD_BY_FORCE, result)
    }
}
