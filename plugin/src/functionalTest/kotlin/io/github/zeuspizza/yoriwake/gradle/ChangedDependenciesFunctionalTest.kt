package io.github.zeuspizza.yoriwake.gradle

import io.github.zeuspizza.yoriwake.agent.contract.AgentContract
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A test runtime classpath that resolves a dependency to another jar than the capture's runs every
 * test, whatever changed it: a file `pathIgnore` leaves out, a property, or nothing in the tree.
 */
class ChangedDependenciesFunctionalTest : FunctionalTestSupport() {

    /** Runs first by name and passes on any version; loads nothing a change to Beta reaches. */
    private val versionTest = "src/test/java/dev/sample/AaVersionTest.java" to """
        package dev.sample;
        import org.apache.commons.lang3.StringUtils;
        import org.junit.jupiter.api.Test;
        import static org.junit.jupiter.api.Assertions.assertNotNull;
        class AaVersionTest {
            @Test void loads() { assertNotNull(StringUtils.class.getPackage().getImplementationVersion()); }
        }
    """.trimIndent()

    /** commons-lang3 at the version [versionFrom] gives, and the two-class sample; committed and captured. */
    private fun captured(dir: File, versionFrom: String, vararg sources: Pair<String, String>, args: List<String> = emptyList()) {
        val buildScript = minimalBuild + "\n" + versionFrom +
            "\ndependencies { testImplementation(\"org.apache.commons:commons-lang3:${'$'}lang3\") }\n"
        build(dir, "build.gradle.kts" to buildScript, oneClass, oneTest, secondClass, secondTest, versionTest, classOrderByName, *sources)
        committed(dir)
        runner(dir, "test", *args.toTypedArray()).build()
    }

    private fun select(dir: File, vararg args: String): String {
        File(dir, "build/test-results").deleteRecursively()
        return runner(dir, "test", "-Pyoriwake.select", "-Pyoriwake.base=HEAD", *args).build().output
    }

    private val fromProperties = """
        val lang3 = file("versions.properties").readLines().first { it.startsWith("commons-lang3=") }.substringAfter("=")
        yoriwake { pathIgnore.addAll("**") }
    """.trimIndent()

    private val fromProjectProperty = """val lang3 = providers.gradleProperty("lang3").getOrElse("3.17.0")"""

    private fun table(dir: File) =
        File(File(dir, ".gradle/yoriwake").listFiles()!!.single(File::isDirectory), "resource-digests")

    private fun assertRanEverything(dir: File, output: String) {
        assertTrue("dev.sample.AaVersionTest" in ranTests(dir), output)
        assertEquals("dependencies-changed", decisionNotes(dir)[AgentContract.REFUSAL_KIND_NOTE], output)
        assertTrue(output.lines().any { it.startsWith("[yoriwake] :test: ") && "commons-lang3" in it }, output)
    }

    @Test
    fun `a dependency version bumped in a properties file the build loads runs every test`(@TempDir dir: File) {
        captured(dir, fromProperties, "versions.properties" to "commons-lang3=3.17.0\n")
        File(dir, "versions.properties").writeText("commons-lang3=3.16.0\n")

        assertRanEverything(dir, select(dir))
    }

    @Test
    fun `a dependency a project property resolves to another version runs every test`(@TempDir dir: File) {
        captured(dir, fromProjectProperty, args = listOf("-Plang3=3.17.0"))
        changeBeta(dir)

        assertRanEverything(dir, select(dir, "-Plang3=3.16.0"))
    }

    @Test
    fun `an unchanged classpath selects as before`(@TempDir dir: File) {
        captured(dir, fromProjectProperty)
        changeBeta(dir)

        repeat(2) {
            val output = select(dir)

            assertEquals(setOf("dev.sample.BetaTest"), ranTests(dir), output)
            assertEquals(null, decisionNotes(dir)[AgentContract.REFUSAL_KIND_NOTE], output)
        }
    }

    @Test
    fun `a map without dependency rows runs every test once`(@TempDir dir: File) {
        captured(dir, fromProjectProperty)
        val rows = table(dir).readLines()
        assertTrue(rows.any { it.startsWith("dependency:") }, rows.joinToString("\n"))
        table(dir).writeText(rows.filterNot { it.startsWith("dependency:") }.joinToString("") { "$it\n" })
        changeBeta(dir)

        val forced = select(dir)

        assertTrue("dev.sample.AaVersionTest" in ranTests(dir), forced)
        assertEquals("classpath-files-unrecorded", decisionNotes(dir)[AgentContract.REFUSAL_KIND_NOTE], forced)
        assertContains(forced, "records no dependencies")

        val narrowed = select(dir)

        assertEquals(setOf("dev.sample.BetaTest"), ranTests(dir), narrowed)
        assertEquals(null, decisionNotes(dir)[AgentContract.REFUSAL_KIND_NOTE], narrowed)
    }

    /** A jar of [entries], with [classPath] as its manifest's `Class-Path` when given. */
    private fun jar(target: File, classPath: String?, vararg entries: Pair<String, String>) {
        target.parentFile.mkdirs()
        val manifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            if (classPath != null) mainAttributes[Attributes.Name.CLASS_PATH] = classPath
        }
        JarOutputStream(target.outputStream(), manifest).use { out ->
            for ((name, content) in entries) {
                out.putNextEntry(JarEntry(name))
                out.write(content.toByteArray())
                out.closeEntry()
            }
        }
    }

    /** Before Beta by name; reads the resource only `vendor.jar`'s manifest puts on the class path. */
    private val vendorTest = "src/test/java/dev/sample/AbVendorTest.java" to """
        package dev.sample;
        import org.junit.jupiter.api.Test;
        import static org.junit.jupiter.api.Assertions.assertNotNull;
        class AbVendorTest {
            @Test void reads() { assertNotNull(getClass().getResource("/vendor.properties")); }
        }
    """.trimIndent()

    /** [name], outside the root, carries no files; its manifest names `lib/a.jar` beside it. */
    private fun vendorCaptured(dir: File, vendor: File, name: String = "vendor.jar") {
        jar(File(vendor, name), "lib/a.jar")
        jar(File(vendor, "lib/a.jar"), null, "vendor.properties" to "flavor=a\n")
        val onClasspath = "dependencies { testRuntimeOnly(files(\"${File(vendor, name).invariantSeparatorsPath}\")) }"
        captured(dir, fromProjectProperty + "\n" + onClasspath, vendorTest)
    }

    @Test
    fun `a file only a dependency's manifest names runs every test when it is replaced`(@TempDir dir: File, @TempDir vendor: File) {
        vendorCaptured(dir, vendor)
        jar(File(vendor, "lib/a.jar"), null, "vendor.properties" to "flavor=b\n")
        changeBeta(dir)

        val output = select(dir)

        assertTrue("dev.sample.AbVendorTest" in ranTests(dir), output)
        assertEquals("dependencies-changed", decisionNotes(dir)[AgentContract.REFUSAL_KIND_NOTE], output)
        assertContains(output, "vendor.jar Class-Path lib/a.jar")
    }

    @Test
    fun `a file an upper-case JAR dependency's manifest names runs every test when it is replaced`(
        @TempDir dir: File,
        @TempDir vendor: File,
    ) {
        vendorCaptured(dir, vendor, "VENDOR.JAR")
        jar(File(vendor, "lib/a.jar"), null, "vendor.properties" to "flavor=b\n")
        changeBeta(dir)

        val output = select(dir)

        assertTrue("dev.sample.AbVendorTest" in ranTests(dir), output)
        assertEquals("dependencies-changed", decisionNotes(dir)[AgentContract.REFUSAL_KIND_NOTE], output)
        assertContains(output, "VENDOR.JAR Class-Path lib/a.jar")
    }

    @Test
    fun `a dependency whose manifest names a file selects as before when nothing changed`(@TempDir dir: File, @TempDir vendor: File) {
        vendorCaptured(dir, vendor)
        changeBeta(dir)

        repeat(2) {
            val output = select(dir)

            assertEquals(setOf("dev.sample.BetaTest"), ranTests(dir), output)
            assertEquals(null, decisionNotes(dir)[AgentContract.REFUSAL_KIND_NOTE], output)
        }
    }

    /**
     * `dev.vendor:vendor:1.0` from a file repository in [repository], on the test runtime classpath
     * only through an artifact transform whose output carries the time it ran, as a transform that
     * does not write its output byte for byte alike on every run does. The transform prints where.
     */
    private fun transformedBuild(repository: File): String = "import java.util.zip.*\n" + minimalBuild + "\n" + """
        val stamped = Attribute.of("stamped", Boolean::class.javaObjectType)
        abstract class Stamp : TransformAction<TransformParameters.None> {
            @get:InputArtifact abstract val input: Provider<FileSystemLocation>
            override fun transform(outputs: TransformOutputs) {
                val jar = input.get().asFile
                val out = outputs.file("stamped-" + jar.name)
                ZipOutputStream(out.outputStream()).use { zip ->
                    ZipFile(jar).use { from ->
                        for (entry in from.entries()) {
                            zip.putNextEntry(ZipEntry(entry.name))
                            from.getInputStream(entry).use { it.copyTo(zip) }
                            zip.closeEntry()
                        }
                    }
                    zip.putNextEntry(ZipEntry("stamp.txt"))
                    zip.write(System.nanoTime().toString().toByteArray())
                    zip.closeEntry()
                }
                println("[stamp] transformed into " + out.absolutePath)
            }
        }
        repositories { maven { url = uri("${repository.invariantSeparatorsPath}") } }
        val vendor by configurations.creating { isCanBeConsumed = false }
        dependencies {
            attributesSchema { attribute(stamped) }
            artifactTypes.getByName("jar").attributes.attribute(stamped, false)
            registerTransform(Stamp::class) {
                from.attribute(stamped, false).attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "jar")
                to.attribute(stamped, true).attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "jar")
            }
            vendor("dev.vendor:vendor:1.0")
            testRuntimeOnly(files(vendor.incoming.artifactView { attributes.attribute(stamped, true) }.files))
        }
    """.trimIndent()

    /** `dev.vendor:vendor:1.0` in a Maven layout under [repository], holding [content]. */
    private fun published(repository: File, content: String) {
        val version = File(repository, "dev/vendor/vendor/1.0")
        jar(File(version, "vendor-1.0.jar"), null, "vendor.properties" to content)
        File(version, "vendor-1.0.pom").writeText(
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>dev.vendor</groupId><artifactId>vendor</artifactId><version>1.0</version>
            </project>
            """.trimIndent()
        )
    }

    /**
     * Where the transform's output lies once the build is done, from the path it printed in
     * [output]; null when it did not run. Gradle 8 runs a transform in `<hash>-<uuid>` and then
     * moves the workspace to `<hash>`.
     */
    private fun transformedInto(output: String): File? = output.lines()
        .firstOrNull { it.startsWith("[stamp] transformed into ") }?.substringAfter("into ")
        ?.replace(Regex("""([/\\]transforms[/\\][0-9a-f]+)-[0-9a-f-]{36}([/\\])"""), "$1$2")?.let(::File)

    /** A selecting run in a new Gradle user home and daemon, as on a fresh CI runner. */
    private fun selectOnAFreshMachine(dir: File, home: File): String {
        File(dir, "build/test-results").deleteRecursively()
        FunctionalCoverage.configure(home)
        return GradleRunner.create()
            .withProjectDir(dir)
            .withPluginClasspath()
            .withGradleVersion(FunctionalGradle.version)
            .withTestKitDir(home)
            .withArguments("test", "-Pyoriwake.select", "-Pyoriwake.base=HEAD", "--configuration-cache", "--stacktrace", "--no-watch-fs")
            .forwardOutput()
            .build().output
    }

    @Test
    fun `a transformed dependency Gradle writes again with other bytes selects as before`(
        @TempDir dir: File,
        @TempDir repository: File,
        @TempDir fresh: File,
    ) {
        published(repository, "flavor=a\n")
        build(dir, "build.gradle.kts" to transformedBuild(repository), oneClass, oneTest, secondClass, secondTest, classOrderByName)
        committed(dir)
        val capture = runner(dir, "test").build().output
        val captured = assertNotNull(transformedInto(capture), capture)
        val capturedBytes = captured.readBytes()
        changeBeta(dir)

        val output = selectOnAFreshMachine(dir, File(fresh, "gradle-home"))

        val selected = assertNotNull(transformedInto(output), "the transform did not run again\n$output")
        assertEquals(
            captured.invariantSeparatorsPath.substringAfter("/caches/"),
            selected.invariantSeparatorsPath.substringAfter("/caches/"),
        )
        assertTrue("/transforms/" in selected.invariantSeparatorsPath, selected.path)
        assertFalse(capturedBytes.contentEquals(selected.readBytes()), "the transform wrote the same bytes again")
        assertEquals(setOf("dev.sample.BetaTest"), ranTests(dir), output)
        assertEquals(null, decisionNotes(dir)[AgentContract.REFUSAL_KIND_NOTE], output)
    }

    @Test
    fun `a transformed dependency whose input changed runs every test`(@TempDir dir: File, @TempDir repository: File) {
        published(repository, "flavor=a\n")
        build(dir, "build.gradle.kts" to transformedBuild(repository), oneClass, oneTest, secondClass, secondTest, classOrderByName)
        committed(dir)
        runner(dir, "test").build()
        published(repository, "flavor=b\n")
        changeBeta(dir)

        val output = select(dir)

        assertEquals(setOf("dev.sample.AlphaTest", "dev.sample.BetaTest"), ranTests(dir), output)
        assertEquals("dependencies-changed", decisionNotes(dir)[AgentContract.REFUSAL_KIND_NOTE], output)
        assertTrue(output.lines().any { it.startsWith("[yoriwake] :test: ") && "/transforms/" in it }, output)
    }

    /** Before Beta by name; reads the resource only the jar under the Gradle user home holds. */
    private val homeJarTest = "src/test/java/dev/sample/AbHomeJarTest.java" to """
        package dev.sample;
        import org.junit.jupiter.api.Test;
        import static org.junit.jupiter.api.Assertions.assertNotNull;
        class AbHomeJarTest {
            @Test void reads() { assertNotNull(getClass().getResource("/home-jar.properties")); }
        }
    """.trimIndent()

    /**
     * A jar at [relative] under the TestKit Gradle user home, on the test runtime classpath, captured;
     * [test] then gets the jar and deletes [cleanup] (relative to that home) afterwards. Gradle writes
     * these layouts only on events a test cannot stage cheaply, so the jar is put there directly.
     */
    private fun withHomeJar(dir: File, relative: String, cleanup: String, test: (File) -> Unit) {
        val home = FunctionalGradle.testKitDir()
        val homeJar = File(home, relative)
        try {
            jar(homeJar, null, "home-jar.properties" to "flavor=a\n")
            val onClasspath = "dependencies { testRuntimeOnly(files(\"${homeJar.invariantSeparatorsPath}\")) }"
            captured(dir, fromProjectProperty + "\n" + onClasspath, homeJarTest)
            test(homeJar)
        } finally {
            File(home, cleanup).deleteRecursively()
        }
    }

    private fun assertHomeJarRanEverything(dir: File, output: String, name: String) {
        assertTrue("dev.sample.AbHomeJarTest" in ranTests(dir), output)
        assertEquals("dependencies-changed", decisionNotes(dir)[AgentContract.REFUSAL_KIND_NOTE], output)
        assertTrue(output.lines().any { it.startsWith("[yoriwake] :test: ") && name in it }, output)
    }

    @Test
    fun `a generated Gradle jar replaced in place runs every test`(@TempDir dir: File) {
        val version = "yoriwake-test-${System.nanoTime()}"
        withHomeJar(dir, "caches/$version/generated-gradle-jars/gradle-api-$version.jar", "caches/$version") { homeJar ->
            jar(homeJar, null, "home-jar.properties" to "flavor=b\n")
            changeBeta(dir)

            assertHomeJarRanEverything(dir, select(dir), "gradle-api-$version.jar")
        }
    }

    @Test
    fun `a wrapper distribution's jar replaced in place runs every test`(@TempDir dir: File) {
        val name = "yoriwake-test-${System.nanoTime()}"
        withHomeJar(dir, "wrapper/dists/$name-bin/abc123/$name/lib/gradle-core-$name.jar", "wrapper/dists/$name-bin") { homeJar ->
            jar(homeJar, null, "home-jar.properties" to "flavor=b\n")
            changeBeta(dir)

            assertHomeJarRanEverything(dir, select(dir), "gradle-core-$name.jar")
        }
    }

    @Test
    fun `a test task on the Gradle API selects as before when nothing changed`(@TempDir dir: File) {
        captured(dir, fromProjectProperty + "\ndependencies { testImplementation(gradleApi()) }")
        changeBeta(dir)

        repeat(2) {
            val output = select(dir)

            assertEquals(setOf("dev.sample.BetaTest"), ranTests(dir), output)
            assertEquals(null, decisionNotes(dir)[AgentContract.REFUSAL_KIND_NOTE], output)
        }
    }
}
