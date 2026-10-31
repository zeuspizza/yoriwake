package io.github.zeuspizza.yoriwake.gradle

import io.github.zeuspizza.yoriwake.agent.contract.AgentContract
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A file the build produces onto the test classpath from something other than tracked sources (a
 * property standing in for the commit, a tag or the clock) runs the tests that read it whenever it
 * changed since the map was captured, though no tracked path changed with it.
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

    /**
     * A jar whose manifest carries the `rev`, `time` and `impl` properties, and which holds
     * `extra.txt` when `extra` is set; rebuilt byte for byte when none of them moves.
     */
    private val libBuild = """
        plugins { `java-library` }
        val extraFiles by tasks.registering {
            val extra = providers.gradleProperty("extra").orElse("")
            val out = layout.buildDirectory.dir("generated/extra")
            inputs.property("extra", extra)
            outputs.dir(out)
            doLast {
                val root = out.get().asFile
                root.deleteRecursively()
                root.mkdirs()
                if (extra.get().isNotEmpty()) root.resolve("extra.txt").writeText(extra.get())
            }
        }
        tasks.jar {
            val rev = providers.gradleProperty("rev").orElse("r1")
            val time = providers.gradleProperty("time").orElse("t1")
            val impl = providers.gradleProperty("impl").orElse("1")
            inputs.property("rev", rev)
            inputs.property("time", time)
            inputs.property("impl", impl)
            from(extraFiles)
            isPreserveFileTimestamps = false
            isReproducibleFileOrder = true
            manifest {
                attributes("Build-Revision" to rev.get(), "Build-Time" to time.get(), "Implementation-Version" to impl.get())
            }
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

    /** Loads a class from `lib`'s jar, so the class loader reads that jar's manifest, and reads nothing else of it. */
    private val libTest = "src/test/java/dev/sample/AdLibTest.java" to """
        package dev.sample;
        import org.junit.jupiter.api.Test;
        import static org.junit.jupiter.api.Assertions.assertNotNull;
        class AdLibTest {
            @Test void loads() { assertNotNull(new dev.lib.Lib()); }
        }
    """.trimIndent()

    /** Reads `lib`'s `Implementation-Version` through its package, which reads no file. */
    private val versionTest = "src/test/java/dev/sample/AeVersionTest.java" to """
        package dev.sample;
        import org.junit.jupiter.api.Test;
        import static org.junit.jupiter.api.Assertions.assertNotNull;
        class AeVersionTest {
            @Test void reads() { assertNotNull(dev.lib.Lib.class.getPackage().getImplementationVersion()); }
        }
    """.trimIndent()

    /**
     * `lib` builds a jar `app`'s tests use at runtime; `app` generates a test resource. Committed.
     * Without [readsManifests], no test reads a manifest's content.
     */
    private fun fixture(
        dir: File,
        appExtra: String = "",
        vararg appSources: Pair<String, String>,
        rootBuild: String = "",
        readsManifests: Boolean = true,
        infoReader: Pair<String, String> = infoTest,
        appImports: String = "",
    ) {
        build(
            dir,
            "build.gradle.kts" to rootBuild,
            "lib/build.gradle.kts" to libBuild,
            "lib/src/main/java/dev/lib/Lib.java" to "package dev.lib;\npublic class Lib {}\n",
            "app/build.gradle.kts" to appImports + minimalBuild + "\n" + appBuild + "\n" + appExtra,
            *arrayOf(
                oneClass, oneTest, secondClass, secondTest, classOrderByName,
                legacy, legacyTest, infoReader, staticResource, *appSources,
                *(if (readsManifests) arrayOf(revisionTest) else emptyArray()),
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
        assertEquals(null, decisionNotes(dir)[AgentContract.REFUSAL_KIND_NOTE], output)
        assertEquals(null, decisionNotes(dir)[AgentContract.FULL_RUN_KIND_NOTE], output)
        assertTrue(
            output.lines().any { it.startsWith("[yoriwake] :app:test: ") && "app/build/resources/test/info.properties" in it },
            output,
        )
    }

    @Test
    fun `a generated resource with a comma in its name runs the test that reads it`(@TempDir dir: File) {
        // A list of moved files is comma-joined on its way to the test JVM.
        val users = """
            val generateUsers by tasks.registering {
                val users = providers.gradleProperty("users").orElse("a")
                val out = layout.buildDirectory.file("generated/users/users,v2.json")
                inputs.property("users", users)
                outputs.file(out)
                doLast { out.get().asFile.apply { parentFile.mkdirs() }.writeText(users.get()) }
            }
            sourceSets.test { resources.srcDir(layout.buildDirectory.dir("generated/users")) }
            tasks.processTestResources { dependsOn(generateUsers) }
        """.trimIndent()
        val reader = "src/test/java/dev/sample/AaInfoTest.java" to """
            package dev.sample;
            import org.junit.jupiter.api.Test;
            import java.io.InputStream;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            class AaInfoTest {
                @Test void reads() throws Exception {
                    String users;
                    try (InputStream in = getClass().getResourceAsStream("/users,v2.json")) { users = new String(in.readAllBytes()); }
                    if ("x".equals(users)) assertEquals(1, new Legacy().value());
                }
            }
        """.trimIndent()
        fixture(dir, appExtra = users, infoReader = reader)
        capture(dir, "-Pusers=a")
        editLegacy(dir)

        val output = select(dir, "-Pusers=x")

        assertReaderRanAlone(dir, output)
    }

    @Test
    fun `a generated resource that changed runs everything when the last look cannot be handed it`(@TempDir dir: File) {
        fixture(dir)
        capture(dir, "-Pinfo=a")
        editLegacy(dir)
        // A directory where the hand-over file goes: writing it fails, so no last look can follow.
        val mapDir = File(dir, ".gradle/yoriwake").listFiles().orEmpty().single { it.name.startsWith("app-test") }
        File(mapDir, "classpath-files.compared/blocked").mkdirs()

        val output = select(dir, "-Pinfo=x")

        assertTrue("could not be handed to classpath-files.compared" in output, output)
        assertTrue("dev.sample.AaInfoTest" in ranTests(app(dir)) && "dev.sample.AlphaTest" in ranTests(app(dir)), output)
    }

    /** [infoTest], opening the generated file by a path it builds: [path] names it from the project directory. */
    private fun infoReadThrough(path: String, before: String = "", after: String = "") =
        "src/test/java/dev/sample/AaInfoTest.java" to """
            package dev.sample;
            import org.junit.jupiter.api.Test;
            import java.io.File;
            import java.io.FileInputStream;
            import java.io.InputStream;
            import java.nio.file.*;
            import java.util.Properties;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            class AaInfoTest {
                @Test void reads() throws Exception {
                    $before
                    Properties info = new Properties();
                    try (InputStream in = new FileInputStream(new File("$path"))) { info.load(in); }
                    $after
                    if ("x".equals(info.getProperty("info"))) assertEquals(1, new Legacy().value());
                }
            }
        """.trimIndent()

    private fun assertReaderRanAlone(dir: File, output: String) {
        assertTrue("dev.sample.AaInfoTest" in ranTests(app(dir)), output)
        assertEquals(null, decisionNotes(dir)[AgentContract.REFUSAL_KIND_NOTE], output)
        assertEquals(null, decisionNotes(dir)[AgentContract.FULL_RUN_KIND_NOTE], output)
    }

    @Test
    fun `a generated resource read through a link the test removes runs the test that reads it`(@TempDir dir: File) {
        fixture(
            dir,
            infoReader = infoReadThrough(
                "build/alias/info.properties",
                before = "Path alias = Paths.get(\"build/alias\"); Files.deleteIfExists(alias); " +
                    "Files.createSymbolicLink(alias, Paths.get(\"resources/test\"));",
                after = "Files.delete(alias);",
            ),
        )
        capture(dir, "-Pinfo=a")
        editLegacy(dir)

        assertReaderRanAlone(dir, select(dir, "-Pinfo=x"))
    }

    @Test
    fun `a generated resource read in another letter case runs the test that reads it`(@TempDir dir: File) {
        val probe = File(dir, "case-probe").apply { writeText("") }
        assumeTrue(File(dir, "CASE-PROBE").exists(), "the file system tells letter cases apart")
        probe.delete()
        fixture(dir, infoReader = infoReadThrough("BUILD/resources/test/info.properties"))
        capture(dir, "-Pinfo=a")
        editLegacy(dir)

        assertReaderRanAlone(dir, select(dir, "-Pinfo=x"))
    }

    @Test
    fun `a resource the test task's own doFirst writes runs the test that reads it`(@TempDir dir: File) {
        fixture(
            dir,
            """
            tasks.test {
                val stamp = providers.gradleProperty("stamp").orElse("a")
                val info = layout.buildDirectory.file("resources/test/info.properties")
                doFirst { info.get().asFile.writeText("info=" + stamp.get() + "\n") }
            }
            """.trimIndent(),
        )
        capture(dir, "-Pstamp=a")
        editLegacy(dir)

        val output = select(dir, "-Pstamp=x")

        assertTrue("dev.sample.AaInfoTest" in ranTests(app(dir)), output)
        assertEquals("classpath-files-changed", decisionNotes(dir)[AgentContract.REFUSAL_KIND_NOTE], output)
        assertTrue(
            output.lines().any {
                it.startsWith("[yoriwake] :app:test: ") && "app/build/resources/test/info.properties" in it &&
                    "doFirst" in it
            },
            output,
        )
    }

    @Test
    fun `a resource a doFirst from the root project's subprojects block writes runs the test that reads it`(@TempDir dir: File) {
        fixture(
            dir,
            rootBuild = """
                subprojects {
                    tasks.withType<Test>().configureEach {
                        val stamp = providers.gradleProperty("stamp").orElse("a")
                        val info = layout.buildDirectory.file("resources/test/info.properties")
                        doFirst { info.get().asFile.writeText("info=" + stamp.get() + "\n") }
                    }
                }
            """.trimIndent(),
        )
        capture(dir, "-Pstamp=a")
        editLegacy(dir)

        val output = select(dir, "-Pstamp=x")

        assertTrue("dev.sample.AaInfoTest" in ranTests(app(dir)), output)
        assertEquals("classpath-files-changed", decisionNotes(dir)[AgentContract.REFUSAL_KIND_NOTE], output)
    }

    /** `path.jar` carries no files; its manifest puts `flavor.jar`, built from `flavor`, on the class path. */
    private val flavorJars = """
        val generateFlavor by tasks.registering {
            val flavor = providers.gradleProperty("flavor").orElse("a")
            val out = layout.buildDirectory.dir("generated/flavor")
            inputs.property("flavor", flavor)
            outputs.dir(out)
            doLast { out.get().file("flavor.properties").asFile.writeText("flavor=" + flavor.get() + "\n") }
        }
        val flavorJar by tasks.registering(Jar::class) {
            archiveFileName.set("flavor.jar")
            destinationDirectory.set(layout.buildDirectory.dir("extra"))
            from(generateFlavor)
        }
        val pathJar by tasks.registering(Jar::class) {
            dependsOn(flavorJar)
            archiveFileName.set("path.jar")
            destinationDirectory.set(layout.buildDirectory.dir("extra"))
            manifest { attributes("Class-Path" to "flavor.jar") }
        }
        dependencies { testRuntimeOnly(files(pathJar)) }
    """.trimIndent()

    /** Before Legacy by name, so Legacy is not loaded yet when it runs under `flavor=a`. */
    private val flavorTest = "src/test/java/dev/sample/AcFlavorTest.java" to """
        package dev.sample;
        import org.junit.jupiter.api.Test;
        import java.io.InputStream;
        import java.util.Properties;
        import static org.junit.jupiter.api.Assertions.assertEquals;
        class AcFlavorTest {
            @Test void reads() throws Exception {
                Properties flavor = new Properties();
                try (InputStream in = getClass().getResourceAsStream("/flavor.properties")) { flavor.load(in); }
                if ("x".equals(flavor.getProperty("flavor"))) assertEquals(1, new Legacy().value());
            }
        }
    """.trimIndent()

    @Test
    fun `a jar only another jar's manifest names runs the test that reads it when it changed`(@TempDir dir: File) {
        fixture(dir, flavorJars, flavorTest)
        capture(dir, "-Pflavor=a")
        editLegacy(dir)

        val output = select(dir, "-Pflavor=x")

        assertTrue("dev.sample.AcFlavorTest" in ranTests(app(dir)), output)
        assertEquals(null, decisionNotes(dir)[AgentContract.REFUSAL_KIND_NOTE], output)
        assertTrue(output.contains("app/build/extra/flavor.jar!/flavor.properties"), output)
    }

    /** Before Legacy by name; reads the resource only `VENDOR.JAR`'s manifest puts on the class path. */
    private val vendorTest = "src/test/java/dev/sample/AcVendorTest.java" to """
        package dev.sample;
        import org.junit.jupiter.api.Test;
        import static org.junit.jupiter.api.Assertions.assertNotNull;
        class AcVendorTest {
            @Test void reads() { assertNotNull(getClass().getResource("/vendor.properties")); }
        }
    """.trimIndent()

    /** A jar at [target] of [entries], with [classPath] as its manifest's `Class-Path` when given. */
    private fun vendorJar(target: File, classPath: String?, vararg entries: Pair<String, String>) {
        target.parentFile.mkdirs()
        val manifest = java.util.jar.Manifest().apply {
            mainAttributes[java.util.jar.Attributes.Name.MANIFEST_VERSION] = "1.0"
            if (classPath != null) mainAttributes[java.util.jar.Attributes.Name.CLASS_PATH] = classPath
        }
        java.util.jar.JarOutputStream(target.outputStream(), manifest).use { out ->
            for ((name, content) in entries) {
                out.putNextEntry(java.util.jar.JarEntry(name))
                out.write(content.toByteArray())
                out.closeEntry()
            }
        }
    }

    @Test
    fun `a jar an upper-case JAR a build jar's manifest names runs the test that reads it when it changed`(
        @TempDir dir: File,
        @TempDir vendor: File,
    ) {
        val upper = File(vendor, "VENDOR.JAR")
        vendorJar(upper, "lib/a.jar")
        vendorJar(File(vendor, "lib/a.jar"), null, "vendor.properties" to "flavor=a\n")
        val pointer = """
            val pointerJar by tasks.registering(Jar::class) {
                archiveFileName.set("pointer.jar")
                destinationDirectory.set(layout.buildDirectory.dir("extra"))
                manifest { attributes("Class-Path" to "${upper.absoluteFile.toURI()}") }
            }
            dependencies { testRuntimeOnly(files(pointerJar)) }
        """.trimIndent()
        fixture(dir, pointer, vendorTest)
        capture(dir)
        vendorJar(File(vendor, "lib/a.jar"), null, "vendor.properties" to "flavor=b\n")
        editLegacy(dir)

        val output = select(dir)

        assertTrue("dev.sample.AcVendorTest" in ranTests(app(dir)), output)
        assertEquals("classpath-files-changed", decisionNotes(dir)[AgentContract.REFUSAL_KIND_NOTE], output)
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
    fun `a revision stamped into a sibling jar's manifest runs the test that reads it`(@TempDir dir: File) {
        fixture(dir)
        capture(dir, "-Prev=r1")
        changeBeta(app(dir))

        val output = select(dir, "-Prev=r2")

        val ran = ranTests(app(dir))
        assertTrue("dev.sample.AbRevisionTest" in ran && "dev.sample.AaInfoTest" !in ran, output)
        assertEquals(null, decisionNotes(dir)[AgentContract.REFUSAL_KIND_NOTE], output)
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

    @Test
    fun `a build time stamped into a sibling jar's manifest that no test reads runs nothing more`(@TempDir dir: File) {
        fixture(dir, "", libTest, readsManifests = false)
        capture(dir, "-Ptime=t1")
        changeBeta(app(dir))

        val output = select(dir, "-Ptime=t2")

        val ran = ranTests(app(dir))
        assertTrue("dev.sample.BetaTest" in ran && "dev.sample.AdLibTest" !in ran, output)
        assertEquals(null, decisionNotes(dir)[AgentContract.REFUSAL_KIND_NOTE], output)
        assertTrue(output.contains("lib/build/libs/lib.jar!/META-INF/MANIFEST.MF"), output)
    }

    @Test
    fun `an implementation version a test reads through its package runs everything`(@TempDir dir: File) {
        fixture(dir, "", versionTest, readsManifests = false)
        capture(dir, "-Pimpl=1")
        changeBeta(app(dir))

        val output = select(dir, "-Pimpl=2")

        assertTrue("dev.sample.AeVersionTest" in ranTests(app(dir)), output)
        assertEquals("classpath-files-changed", decisionNotes(dir)[AgentContract.REFUSAL_KIND_NOTE], output)
    }

    @Test
    fun `a file that appeared in a sibling jar runs everything`(@TempDir dir: File) {
        fixture(dir, "", libTest, readsManifests = false)
        capture(dir)
        changeBeta(app(dir))

        val output = select(dir, "-Pextra=new")

        assertTrue("dev.sample.AdLibTest" in ranTests(app(dir)), output)
        assertEquals("classpath-files-changed", decisionNotes(dir)[AgentContract.REFUSAL_KIND_NOTE], output)
        assertTrue(output.contains("lib/build/libs/lib.jar!/extra.txt"), output)
    }

    /** Reads `lib`'s jar whole, with its own zip code, and nothing else of it. */
    private val wholeJarTest = "src/test/java/dev/sample/AfWholeJarTest.java" to """
        package dev.sample;
        import org.junit.jupiter.api.Test;
        import java.io.FileInputStream;
        import java.util.jar.JarInputStream;
        import static org.junit.jupiter.api.Assertions.assertNotNull;
        class AfWholeJarTest {
            @Test void reads() throws Exception {
                try (JarInputStream jar = new JarInputStream(new FileInputStream("../lib/build/libs/lib.jar"))) {
                    assertNotNull(jar.getManifest().getMainAttributes().getValue("Build-Time"));
                }
            }
        }
    """.trimIndent()

    @Test
    fun `a build time a test reads from the whole jar runs that test`(@TempDir dir: File) {
        fixture(dir, "", wholeJarTest, libTest, readsManifests = false)
        capture(dir, "-Ptime=t1")
        changeBeta(app(dir))

        val output = select(dir, "-Ptime=t2")

        assertTrue("dev.sample.AfWholeJarTest" in ranTests(app(dir)), output)
        assertEquals(null, decisionNotes(dir)[AgentContract.REFUSAL_KIND_NOTE], output)
    }

    @Test
    fun `a file gone from a sibling jar runs everything`(@TempDir dir: File) {
        fixture(dir, "", libTest, readsManifests = false)
        capture(dir, "-Pextra=old")
        changeBeta(app(dir))

        val output = select(dir)

        assertTrue("dev.sample.AdLibTest" in ranTests(app(dir)), output)
        assertEquals("classpath-files-changed", decisionNotes(dir)[AgentContract.REFUSAL_KIND_NOTE], output)
        assertTrue(output.contains("lib/build/libs/lib.jar!/extra.txt (gone)"), output)
    }

    /** Opens `/launcher`, a shell script with a zip appended, with the JDK's zip code. */
    private val launcherTest = "src/test/java/dev/sample/AgLauncherTest.java" to """
        package dev.sample;
        import org.junit.jupiter.api.Test;
        import java.io.File;
        import java.util.zip.ZipFile;
        import static org.junit.jupiter.api.Assertions.assertNotNull;
        class AgLauncherTest {
            @Test void reads() throws Exception {
                try (ZipFile zip = new ZipFile(new File(getClass().getResource("/launcher").toURI()))) {
                    assertNotNull(zip.getEntry("greeting.txt"));
                }
            }
        }
    """.trimIndent()

    /** Writes `launcher` from the `launcher` property into a test resource directory under build/. */
    private val launcherBuild = """
        val generateLauncher by tasks.registering {
            val greeting = providers.gradleProperty("launcher").orElse("a")
            val out = layout.buildDirectory.file("generated/launcher/launcher")
            inputs.property("greeting", greeting)
            outputs.file(out)
            doLast {
                out.get().asFile.apply { parentFile.mkdirs() }.outputStream().use { stream ->
                    stream.write("#!/bin/sh\n# runs the archive appended to this script\n".toByteArray())
                    ZipOutputStream(stream).use { zip ->
                        zip.putNextEntry(ZipEntry("greeting.txt").apply { time = 0L })
                        zip.write(greeting.get().toByteArray())
                        zip.closeEntry()
                    }
                }
            }
        }
        sourceSets.test { resources.srcDir(layout.buildDirectory.dir("generated/launcher")) }
        tasks.processTestResources { dependsOn(generateLauncher) }
    """.trimIndent()

    @Test
    fun `a zip behind a launch script in a resource directory runs everything when it changed`(@TempDir dir: File) {
        fixture(
            dir, launcherBuild, launcherTest, readsManifests = false,
            // `java` in a build script names the Java extension, so the zip classes are imported.
            appImports = "import java.util.zip.ZipEntry\nimport java.util.zip.ZipOutputStream\n",
        )
        capture(dir, "-Plauncher=a")
        changeBeta(app(dir))

        val output = select(dir, "-Plauncher=b")

        assertTrue("dev.sample.AgLauncherTest" in ranTests(app(dir)), output)
        assertEquals("classpath-files-changed", decisionNotes(dir)[AgentContract.REFUSAL_KIND_NOTE], output)
    }

    @Test
    fun `a map that records no reads runs everything on a moved stamp`(@TempDir dir: File) {
        fixture(dir, "", libTest, readsManifests = false)
        capture(dir, "-Ptime=t1")
        assertTrue(File(table(dir).parentFile, AgentContract.RESOURCE_TOUCH_FILE).delete())
        changeBeta(app(dir))

        val output = select(dir, "-Ptime=t2")

        assertTrue("dev.sample.AdLibTest" in ranTests(app(dir)), output)
        assertEquals("classpath-files-changed", decisionNotes(dir)[AgentContract.REFUSAL_KIND_NOTE], output)
    }

    /** A manifest in a test resource directory, as a bundle plugin extracts one; the class loader never reads it. */
    private val directoryManifest = """
        val generateBundle by tasks.registering {
            val bundle = providers.gradleProperty("bundle").orElse("a")
            val out = layout.buildDirectory.dir("generated/bundle")
            inputs.property("bundle", bundle)
            outputs.dir(out)
            doLast {
                out.get().file("META-INF/MANIFEST.MF").asFile.apply { parentFile.mkdirs() }
                    .writeText("Manifest-Version: 1.0\nBundle-Version: " + bundle.get() + "\n")
            }
        }
        sourceSets.test { resources.srcDir(generateBundle) }
    """.trimIndent()

    @Test
    fun `a header of a manifest in a classpath directory that no test reads runs nothing more`(@TempDir dir: File) {
        fixture(dir, directoryManifest, readsManifests = false)
        capture(dir, "-Pbundle=a")
        changeBeta(app(dir))

        val output = select(dir, "-Pbundle=b")

        val ran = ranTests(app(dir))
        assertTrue("dev.sample.BetaTest" in ran && "dev.sample.AaInfoTest" !in ran, output)
        assertEquals(null, decisionNotes(dir)[AgentContract.REFUSAL_KIND_NOTE], output)
        assertTrue(output.contains("META-INF/MANIFEST.MF"), output)
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

        assertEquals("empty-change-set", decisionNotes(dir)[AgentContract.FULL_RUN_KIND_NOTE], forced)
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
    fun `yoriwakeExplain runs the readers of a generated file that changed, as the run does`(@TempDir dir: File) {
        fixture(dir)
        capture(dir, "-Pinfo=a")
        editLegacy(dir)

        val json = explainJson(dir, "-Pinfo=x")

        assertTrue("\"fullRun\": false" in json, json)
        assertTrue("\"refusalKind\": null" in json, json)
    }

    @Test
    fun `yoriwakeExplain forces on a file that appeared, as the run does`(@TempDir dir: File) {
        fixture(dir, "", libTest, readsManifests = false)
        capture(dir)
        changeBeta(app(dir))

        val json = explainJson(dir, "-Pextra=new")

        assertTrue("\"fullRun\": true" in json, json)
        assertTrue("\"refusalKind\": \"classpath-files-changed\"" in json, json)
        assertTrue("lib/build/libs/lib.jar!/extra.txt" in json, json)
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
        changeBeta(app(dir))
        val output = select(dir, "-Pinfo=x")
        assertTrue(output.contains("app/build/resources/test/info.properties (changed)"), output)
    }

    /** A test that reads a tracked text resource, one that does not, and a commit holding both. */
    private fun textResourceProject(dir: File) {
        build(
            dir,
            "build.gradle.kts" to minimalBuild,
            oneClass,
            betaClass,
            "src/test/resources/data.txt" to "one\ntwo\n",
            "src/test/java/dev/sample/AlphaTest.java" to """
                package dev.sample;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertEquals;
                class AlphaTest {
                    @Test void reads() throws Exception {
                        assertEquals(2, new Alpha().twice(1));
                        assertEquals(true, getClass().getResource("/data.txt") != null);
                    }
                }
            """.trimIndent(),
            "src/test/java/dev/sample/BetaTest.java" to """
                package dev.sample;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertEquals;
                class BetaTest {
                    @Test void triples() { assertEquals(3, new Beta().thrice(1)); }
                }
            """.trimIndent(),
        )
        committed(dir)
    }

    /** Captures in a clone with [capturing] as `core.autocrlf` and selects in one with [selecting]. */
    private fun acrossCheckouts(root: File, capturing: Boolean, selecting: Boolean): String {
        val origin = File(root, "origin").apply { mkdirs() }
        textResourceProject(origin)
        val captures = File(root, "captures")
        val selects = File(root, "selects")
        git(root, "clone", "-q", "-c", "core.autocrlf=$capturing", origin.absolutePath, captures.absolutePath)
        git(root, "clone", "-q", "-c", "core.autocrlf=$selecting", origin.absolutePath, selects.absolutePath)
        runner(captures, "test").build()
        File(captures, ".gradle/yoriwake").copyRecursively(File(selects, ".gradle/yoriwake"))
        changeBeta(selects)

        val output = runner(selects, "test", "-Pyoriwake.select").build().output

        assertEquals(setOf("dev.sample.BetaTest"), ranTests(selects), output)
        return output
    }

    @Test
    fun `a map captured in an autocrlf checkout selects in a checkout without it`(@TempDir root: File) {
        val output = acrossCheckouts(root, capturing = true, selecting = false)

        assertTrue("classpath-files-changed" !in output, output)
    }

    @Test
    fun `a map captured without autocrlf selects in an autocrlf checkout`(@TempDir root: File) {
        val output = acrossCheckouts(root, capturing = false, selecting = true)

        assertTrue("classpath-files-changed" !in output, output)
    }

    @Test
    fun `a generated resource that differs only in its line endings is still a changed file`(@TempDir dir: File) {
        textResourceProject(dir)
        File(dir, "build.gradle.kts").appendText(
            """

            val generated by tasks.registering {
                val eol = providers.gradleProperty("eol").orElse("lf")
                val out = layout.buildDirectory.file("generated/text/generated.txt")
                inputs.property("eol", eol)
                outputs.file(out)
                doLast {
                    val end = if (eol.get() == "crlf") "\r\n" else "\n"
                    out.get().asFile.apply { parentFile.mkdirs() }.writeText("one" + end + "two" + end)
                }
            }
            sourceSets.test { resources.srcDir(layout.buildDirectory.dir("generated/text")) }
            tasks.processTestResources { dependsOn(generated) }
            """.trimIndent()
        )
        commit(dir, "generated text")
        // data.txt is checked out with '\r\n', the generated file's line endings with -Peol=crlf.
        git(dir, "config", "core.autocrlf", "true")
        File(dir, "src/test/resources/data.txt").delete()
        git(dir, "checkout", "--", "src/test/resources/data.txt")
        runner(dir, "test").build()
        File(dir, "build/test-results").deleteRecursively()
        changeBeta(dir)

        val output = runner(dir, "test", "-Pyoriwake.select", "-Peol=crlf").build().output

        // Its content is data.txt's with line endings aside, but it is no copy of data.txt.
        assertContains(output, "generated.txt (changed)")
        // No test read it, so only the change's own test runs.
        assertEquals(setOf("dev.sample.BetaTest"), ranTests(dir), output)
    }
}
