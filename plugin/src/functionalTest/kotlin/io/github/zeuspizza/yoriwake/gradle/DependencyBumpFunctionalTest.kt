package io.github.zeuspizza.yoriwake.gradle

import io.github.zeuspizza.yoriwake.agent.contract.AgentContract
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * With `narrowDependencies`, a commit that only bumps library versions runs the tests whose JVM
 * reached the moved jars; without it, and for anything a bump can do that nothing recorded bounds,
 * every test runs. The libraries come from a file repository inside the build's root, where Gradle
 * uses their jars in place, so they lie under the root and must still count as libraries.
 */
class DependencyBumpFunctionalTest : FunctionalTestSupport() {

    /** `dev.lib:<name>:<version>` in the fixture's repository, holding [entries], depending on [dependencies]. */
    private fun library(dir: File, name: String, version: String, entries: Map<String, String>, dependencies: List<String> = emptyList()) {
        val base = File(dir, "repo/dev/lib/$name/$version").apply { mkdirs() }
        ZipOutputStream(File(base, "$name-$version.jar").outputStream()).use { zip ->
            for ((entry, content) in entries) {
                zip.putNextEntry(ZipEntry(entry))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        val deps = dependencies.joinToString("") { dependency ->
            val (artifact, depVersion) = dependency.split(':')
            "<dependency><groupId>dev.lib</groupId><artifactId>$artifact</artifactId><version>$depVersion</version></dependency>"
        }
        File(base, "$name-$version.pom").writeText(
            """<project><modelVersion>4.0.0</modelVersion><groupId>dev.lib</groupId><artifactId>$name</artifactId>""" +
                """<version>$version</version><dependencies>$deps</dependencies></project>"""
        )
    }

    private fun libraries(dir: File) {
        library(dir, "lib-json", "1.0", mapOf("lib/json/data.txt" to "1.0"))
        library(dir, "lib-json", "1.1", mapOf("lib/json/data.txt" to "1.1"))
        library(dir, "lib-json", "1.2", mapOf("lib/json/data.txt" to "1.2", "META-INF/services/dev.Spi" to "dev.Impl\n"))
        library(dir, "lib-json", "1.3", mapOf("lib/json/data.txt" to "1.3"), dependencies = listOf("lib-extra:1.0"))
        library(dir, "lib-extra", "1.0", mapOf("lib/extra/data.txt" to "1.0"))
        library(dir, "lib-math", "1.0", mapOf("lib/math/data.txt" to "1.0"))
        library(dir, "lib-math", "1.1", mapOf("lib/math/data.txt" to "1.0", "lib/math/Fast.class" to "not a class"))
        library(dir, "lib-agent", "1.0", mapOf("agent/data.txt" to "1.0"))
        library(dir, "lib-agent", "1.1", mapOf("agent/data.txt" to "1.1"))
    }

    private fun catalog(json: String = "1.0", math: String = "1.0", agent: String = "1.0", kotlin: String = "2.0.0") = """
        [versions]
        lib-json = "$json"
        kotlin = "$kotlin"

        [libraries]
        lib-json = { module = "dev.lib:lib-json", version.ref = "lib-json" }
        lib-math = { module = "dev.lib:lib-math", version = "$math" }
        lib-agent = { module = "dev.lib:lib-agent", version = "$agent" }

        [plugins]
        kotlin = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
    """.trimIndent()

    private fun buildScript(extra: String = "") = minimalBuild.replace(
        "repositories { mavenCentral() }",
        "repositories {\n    mavenCentral()\n    maven { url = uri(\"repo\") }\n}",
    ) + """

        val agent by configurations.creating
        dependencies {
            testImplementation(libs.lib.json)
            testImplementation(libs.lib.math)
            agent(libs.lib.agent)
        }
    """.trimIndent() + "\n" + extra

    private fun javaTest(name: String, body: String) = "src/test/java/dev/sample/$name.java" to """
        package dev.sample;
        import org.junit.jupiter.api.Test;
        import static org.junit.jupiter.api.Assertions.*;
        class $name {
            @Test void runs() throws Exception { $body }
        }
    """.trimIndent()

    /** Runs first by name and reaches no library. */
    private val earlyTest = javaTest("AaEarlyTest", "assertEquals(2, new Alpha().twice(1));")
    private val jsonTest = javaTest("JsonTest", "assertNotNull(getClass().getResource(\"/lib/json/data.txt\"));")
    private val mathTest = javaTest("MathTest", "assertNotNull(getClass().getResource(\"/lib/math/data.txt\"));")

    /** Looks for an optional class and falls back when it is absent, reaching no library otherwise. */
    private val probeTest = javaTest(
        "ProbeTest",
        "boolean fast; try { Class.forName(\"lib.math.Fast\"); fast = true; } catch (Throwable absent) { fast = false; } assertTrue(fast || !fast);",
    )

    /** Asks every jar for a service file, as `ServiceLoader` does. */
    private val serviceTest = javaTest(
        "ServiceTest",
        "java.util.Collections.list(getClass().getClassLoader().getResources(\"META-INF/services/dev.Spi\"));",
    )

    /** The fixture committed and captured in isolation, one JVM per test class. */
    private fun captured(dir: File, extra: String = "", vararg tests: Pair<String, String>) {
        libraries(dir)
        build(
            dir, "build.gradle.kts" to buildScript(extra), "gradle/libs.versions.toml" to catalog(),
            oneClass, earlyTest, jsonTest, mathTest, *tests,
        )
        committed(dir)
        runner(dir, "test", "-Pyoriwake.isolatedCapture").build()
    }

    private fun select(dir: File, vararg args: String): String {
        File(dir, "build/test-results").deleteRecursively()
        return runner(dir, "test", "-Pyoriwake.select", "-Pyoriwake.base=HEAD", *args).build().output
    }

    private fun bump(dir: File, catalog: String) = File(dir, "gradle/libs.versions.toml").writeText(catalog)

    private val all = setOf("dev.sample.AaEarlyTest", "dev.sample.JsonTest", "dev.sample.MathTest")

    @Test
    fun `a catalog bump runs the tests that reached the moved jar, and only with the flag`(@TempDir dir: File) {
        captured(dir)
        bump(dir, catalog(json = "1.1"))

        val narrowed = select(dir, "-Pyoriwake.narrowDependencies")

        assertEquals(setOf("dev.sample.JsonTest"), ranTests(dir), narrowed)
        assertContains(narrowed, "change dependency versions only")

        val withoutFlag = select(dir)

        assertEquals(all, ranTests(dir), withoutFlag)
        assertContains(withoutFlag, "libs.versions.toml")
    }

    @Test
    fun `a version key a plugin shares runs every test, naming the plugin`(@TempDir dir: File) {
        captured(dir)
        bump(dir, catalog(kotlin = "2.0.1"))

        val output = select(dir, "-Pyoriwake.narrowDependencies")

        assertEquals(all, ranTests(dir), output)
        assertContains(output, "plugin")
    }

    @Test
    fun `a bump that puts a new library on the classpath runs every test`(@TempDir dir: File) {
        captured(dir)
        bump(dir, catalog(json = "1.3"))

        val output = select(dir, "-Pyoriwake.narrowDependencies")

        assertEquals(all, ranTests(dir), output)
        assertEquals("dependency-added", decisionNotes(dir)[AgentContract.REFUSAL_KIND_NOTE], output)
    }

    @Test
    fun `a class a test probed for and did not find runs that test when a bump adds it`(@TempDir dir: File) {
        captured(dir, "", probeTest)
        bump(dir, catalog(math = "1.1"))

        val output = select(dir, "-Pyoriwake.narrowDependencies")

        assertEquals(setOf("dev.sample.MathTest", "dev.sample.ProbeTest"), ranTests(dir), output)
    }

    @Test
    fun `a service file a bump adds runs the test that asked every jar for it`(@TempDir dir: File) {
        captured(dir, "", serviceTest)
        bump(dir, catalog(json = "1.2"))

        val output = select(dir, "-Pyoriwake.narrowDependencies")

        // Coarse, toward running: every JVM whose engine discovery asked for a service file in the
        // directory the bump changed runs too.
        assertTrue(ranTests(dir).containsAll(setOf("dev.sample.JsonTest", "dev.sample.ServiceTest")), output)
    }

    @Test
    fun `a version a build script expands into a resource runs every test, naming the script`(@TempDir dir: File) {
        captured(dir, "tasks.processTestResources { inputs.property(\"v\", libs.versions.lib.json.get()) }")
        bump(dir, catalog(json = "1.1"))

        val output = select(dir, "-Pyoriwake.narrowDependencies")

        assertEquals(all, ranTests(dir), output)
        assertContains(output, "build.gradle.kts reads the version `lib-json`")
    }

    @Test
    fun `a bump of a library only an agent configuration holds runs every test`(@TempDir dir: File) {
        captured(dir)
        bump(dir, catalog(agent = "1.1"))

        val output = select(dir, "-Pyoriwake.narrowDependencies")

        assertEquals(all, ranTests(dir), output)
        assertEquals("dependency-unlocated", decisionNotes(dir)[AgentContract.REFUSAL_KIND_NOTE], output)
    }

    @Test
    fun `a build script edit that is no version bump runs every test, flag or not`(@TempDir dir: File) {
        captured(dir)
        File(dir, "build.gradle.kts").writeText(File(dir, "build.gradle.kts").readText().replace("useJUnitPlatform()", "useJUnitPlatform { }"))

        val output = select(dir, "-Pyoriwake.narrowDependencies")

        assertEquals(all, ranTests(dir), output)
        assertContains(output, "is not a dependency version bump")
    }
}
