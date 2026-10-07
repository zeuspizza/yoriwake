package io.github.zeuspizza.yoriwake.gradle

import io.github.zeuspizza.yoriwake.agent.contract.AgentContract
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A file the build produces onto the test classpath from something other than tracked sources (a
 * property standing in for the commit, a tag or the clock) runs the tests whenever it changed since
 * the map was captured, though no tracked path changed with it.
 */
class GeneratedClasspathFilesFunctionalTest : FunctionalTestSupport() {

    private fun app(dir: File) = File(dir, "app")

    /** Writes `info.properties` from the `info` property into a test resource directory under build/. */
    private val appBuild = """
        dependencies { implementation(project(":lib")) }
        val generateInfo by tasks.registering {
            val info = providers.gradleProperty("info").orElse("a")
            val out = layout.buildDirectory.file("generated/info/info.properties")
            inputs.property("info", info)
            outputs.file(out)
            doLast { out.get().asFile.apply { parentFile.mkdirs() }.writeText("info=" + info.get() + "\n") }
        }
        sourceSets.test { resources.srcDir(layout.buildDirectory.dir("generated/info")) }
        tasks.processTestResources { dependsOn(generateInfo) }
    """.trimIndent()

    /** A jar whose manifest carries the `rev` property, rebuilt byte for byte when it does not move. */
    private val libBuild = """
        plugins { `java-library` }
        tasks.jar {
            val rev = providers.gradleProperty("rev").orElse("r1")
            inputs.property("rev", rev)
            isPreserveFileTimestamps = false
            isReproducibleFileOrder = true
            manifest { attributes("Build-Revision" to rev.get()) }
        }
    """.trimIndent()

    private val legacy = "src/main/java/dev/sample/Legacy.java" to """
        package dev.sample;
        public class Legacy { public int value() { return 1; } }
    """.trimIndent()

    private val legacyTest = "src/test/java/dev/sample/LegacyTest.java" to """
        package dev.sample;
        import org.junit.jupiter.api.Test;
        import static org.junit.jupiter.api.Assertions.assertEquals;
        class LegacyTest {
            @Test void value() { assertEquals(1, new Legacy().value()); }
        }
    """.trimIndent()

    /** First by name, so Legacy is not loaded yet when it runs under `info=a`. */
    private val infoTest = "src/test/java/dev/sample/AaInfoTest.java" to """
        package dev.sample;
        import org.junit.jupiter.api.Test;
        import java.io.InputStream;
        import java.util.Properties;
        import static org.junit.jupiter.api.Assertions.assertEquals;
        class AaInfoTest {
            @Test void reads() throws Exception {
                Properties info = new Properties();
                try (InputStream in = getClass().getResourceAsStream("/info.properties")) { info.load(in); }
                if ("x".equals(info.getProperty("info"))) assertEquals(1, new Legacy().value());
            }
        }
    """.trimIndent()

    private val revisionTest = "src/test/java/dev/sample/AbRevisionTest.java" to """
        package dev.sample;
        import org.junit.jupiter.api.Test;
        import java.io.InputStream;
        import java.net.URL;
        import java.util.Enumeration;
        import java.util.jar.Manifest;
        import static org.junit.jupiter.api.Assertions.assertNotNull;
        class AbRevisionTest {
            @Test void reads() throws Exception {
                String revision = null;
                Enumeration<URL> manifests = getClass().getClassLoader().getResources("META-INF/MANIFEST.MF");
                while (manifests.hasMoreElements()) {
                    try (InputStream in = manifests.nextElement().openStream()) {
                        String found = new Manifest(in).getMainAttributes().getValue("Build-Revision");
                        if (found != null) revision = found;
                    }
                }
                assertNotNull(revision);
            }
        }
    """.trimIndent()

    private val staticResource = "src/test/resources/static.txt" to "one\n"

    /** `lib` builds a jar `app`'s tests use at runtime; `app` generates a test resource. Committed. */
    private fun fixture(dir: File) {
        build(
            dir,
            "build.gradle.kts" to "",
            "lib/build.gradle.kts" to libBuild,
            "lib/src/main/java/dev/lib/Lib.java" to "package dev.lib;\npublic class Lib {}\n",
            "app/build.gradle.kts" to minimalBuild + "\n" + appBuild,
            *arrayOf(
                oneClass, oneTest, secondClass, secondTest, classOrderByName,
                legacy, legacyTest, infoTest, revisionTest, staticResource,
            ).map { (path, content) -> "app/$path" to content }.toTypedArray(),
        )
        File(dir, "settings.gradle.kts").writeText("rootProject.name = \"sample\"\ninclude(\"app\", \"lib\")\n")
        committed(dir)
    }

    private fun capture(dir: File, vararg args: String) {
        runner(dir, ":app:test", *args).build()
    }

    private fun select(dir: File, vararg args: String): String {
        File(app(dir), "build/test-results").deleteRecursively()
        return runner(dir, ":app:test", "-Pyoriwake.select", *args).build().output
    }

    private fun editLegacy(dir: File) {
        File(app(dir), "src/main/java/dev/sample/Legacy.java").writeText(
            """
            package dev.sample;
            public class Legacy { public int value() { return 2 - 1; } }
            """.trimIndent()
        )
    }

    @Test
    fun `a generated resource that changed runs the test that reads it`(@TempDir dir: File) {
        fixture(dir)
        capture(dir, "-Pinfo=a")
        editLegacy(dir)

        val output = select(dir, "-Pinfo=x")

        assertTrue("dev.sample.AaInfoTest" in ranTests(app(dir)), output)
        assertEquals("classpath-files-changed", decisionNotes(dir)[AgentContract.REFUSAL_KIND_NOTE], output)
        assertTrue(
            output.lines().any { it.startsWith("[yoriwake] :app:test: ") && "app/build/resources/test/info.properties" in it },
            output,
        )
    }

    @Test
    fun `an unchanged generated resource narrows as before`(@TempDir dir: File) {
        fixture(dir)
        capture(dir, "-Pinfo=a")
        changeBeta(app(dir))

        val output = select(dir, "-Pinfo=a")

        val ran = ranTests(app(dir))
        assertTrue("dev.sample.BetaTest" in ran && "dev.sample.AaInfoTest" !in ran, output)
        assertEquals(null, decisionNotes(dir)[AgentContract.REFUSAL_KIND_NOTE], output)
    }

    @Test
    fun `a revision stamped into a sibling jar's manifest runs everything`(@TempDir dir: File) {
        fixture(dir)
        capture(dir, "-Prev=r1")
        changeBeta(app(dir))

        val output = select(dir, "-Prev=r2")

        assertTrue("dev.sample.AbRevisionTest" in ranTests(app(dir)), output)
        assertEquals("classpath-files-changed", decisionNotes(dir)[AgentContract.REFUSAL_KIND_NOTE], output)
        assertTrue(output.contains("lib/build/libs/lib.jar!/META-INF/MANIFEST.MF"), output)
    }

    @Test
    fun `an edited test resource forces on its source path as before`(@TempDir dir: File) {
        fixture(dir)
        capture(dir)
        File(app(dir), "src/test/resources/static.txt").writeText("two\n")

        val output = select(dir)

        assertTrue("dev.sample.AaInfoTest" in ranTests(app(dir)), output)
        assertEquals("unmappable-paths", decisionNotes(dir)[AgentContract.FULL_RUN_KIND_NOTE], output)
        assertTrue(
            output.lines().any { it.startsWith("[yoriwake] :app:test: ") && "app/build/resources/test/static.txt" in it },
            output,
        )
    }

    private fun table(dir: File) =
        File(File(dir, ".gradle/yoriwake").listFiles()!!.single(File::isDirectory), "resource-digests")

    @Test
    fun `a map without classpath digests runs everything once`(@TempDir dir: File) {
        fixture(dir)
        capture(dir)
        assertTrue(table(dir).delete())
        changeBeta(app(dir))

        val forced = select(dir)

        assertTrue("dev.sample.AaInfoTest" in ranTests(app(dir)), forced)
        assertEquals("classpath-files-unrecorded", decisionNotes(dir)[AgentContract.REFUSAL_KIND_NOTE], forced)
        assertTrue(table(dir).isFile, forced)

        val narrowed = select(dir)

        assertTrue("dev.sample.AaInfoTest" !in ranTests(app(dir)), narrowed)
        assertEquals(null, decisionNotes(dir)[AgentContract.REFUSAL_KIND_NOTE], narrowed)
    }

    @Test
    fun `a map without classpath digests whose only change is a generated file narrows after one forced run`(
        @TempDir dir: File,
    ) {
        fixture(dir)
        capture(dir, "-Pinfo=a")
        assertTrue(table(dir).delete())

        val forced = select(dir, "-Pinfo=x")

        assertEquals("classpath-files-unrecorded", decisionNotes(dir)[AgentContract.REFUSAL_KIND_NOTE], forced)
        // A change to narrow on: with none at all, the run is full anyway.
        changeBeta(app(dir))

        val narrowed = select(dir, "-Pinfo=x")

        assertTrue("dev.sample.AaInfoTest" !in ranTests(app(dir)), narrowed)
        assertEquals(null, decisionNotes(dir)[AgentContract.REFUSAL_KIND_NOTE], narrowed)
    }

    @Test
    fun `a map whose only change is a generated file narrows after one forced run`(@TempDir dir: File) {
        fixture(dir)
        capture(dir, "-Pinfo=a")
        val before = table(dir).readBytes()

        val forced = select(dir, "-Pinfo=x")

        assertEquals("classpath-files-changed", decisionNotes(dir)[AgentContract.REFUSAL_KIND_NOTE], forced)
        assertTrue(!before.contentEquals(table(dir).readBytes()), forced)
        // A change to narrow on: with none at all, the run is full anyway.
        changeBeta(app(dir))

        val narrowed = select(dir, "-Pinfo=x")

        assertTrue("dev.sample.AaInfoTest" !in ranTests(app(dir)), narrowed)
        assertEquals(null, decisionNotes(dir)[AgentContract.REFUSAL_KIND_NOTE], narrowed)
    }

    /** explain.json as `yoriwakeExplain` writes it now. */
    private fun explainJson(dir: File, vararg args: String): String {
        runner(dir, ":app:yoriwakeExplainTest", *args).build()
        return File(table(dir).parentFile, "explain.json").readText()
    }

    @Test
    fun `yoriwakeExplain forces on a generated file that changed, as the run does`(@TempDir dir: File) {
        fixture(dir)
        capture(dir, "-Pinfo=a")
        editLegacy(dir)

        val json = explainJson(dir, "-Pinfo=x")

        assertTrue("\"fullRun\": true" in json, json)
        assertTrue("\"refusalKind\": \"classpath-files-changed\"" in json, json)
        assertTrue("app/build/resources/test/info.properties" in json, json)
    }

    @Test
    fun `yoriwakeExplain forces on a map without classpath digests, as the run does`(@TempDir dir: File) {
        fixture(dir)
        capture(dir)
        assertTrue(table(dir).delete())
        changeBeta(app(dir))

        val json = explainJson(dir)

        assertTrue("\"fullRun\": true" in json, json)
        assertTrue("\"refusalKind\": \"classpath-files-unrecorded\"" in json, json)
    }

    @Test
    fun `a file a test writes into a classpath directory during the capture is never recorded`(@TempDir dir: File) {
        fixture(dir)
        File(app(dir), "src/test/java/dev/sample/AaaWritesResourceTest.java").apply { parentFile.mkdirs() }.writeText(
            """
            package dev.sample;
            import org.junit.jupiter.api.Test;
            import java.nio.file.Files;
            import java.nio.file.Path;
            class AaaWritesResourceTest {
                @Test void writes() throws Exception {
                    Files.writeString(Path.of("build/resources/test/written.txt"), "at " + System.nanoTime());
                }
            }
            """.trimIndent()
        )
        commit(dir, "a test that writes into its classpath")
        capture(dir)
        // As a fresh checkout would build it: without the file the capture's tests wrote.
        assertTrue(File(app(dir), "build/resources/test/written.txt").delete())
        changeBeta(app(dir))

        val output = select(dir)

        assertTrue("dev.sample.AaInfoTest" !in ranTests(app(dir)), output)
        assertEquals(null, decisionNotes(dir)[AgentContract.REFUSAL_KIND_NOTE], output)
    }

    @Test
    fun `a filtered capture leaves the classpath digests as the last full capture wrote them`(@TempDir dir: File) {
        fixture(dir)
        capture(dir, "-Pinfo=a")
        val before = table(dir).readBytes()

        capture(dir, "-Pinfo=x", "--tests", "dev.sample.AlphaTest")

        assertTrue(before.contentEquals(table(dir).readBytes()))
        val output = select(dir, "-Pinfo=x")
        assertEquals("classpath-files-changed", decisionNotes(dir)[AgentContract.REFUSAL_KIND_NOTE], output)
    }
}
