package io.github.zeuspizza.yoriwake.gradle

import io.github.zeuspizza.yoriwake.gradle.wiring.ParallelismDetector
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Every route a build can enable in-JVM parallelism, because a guard that covers some of them
 * fails open: attribution blends silently and the map looks fine.
 */
class ParallelismDetectorTest {

    private fun configJar(dir: File, name: String, enabled: Boolean): File {
        val jar = File(dir, name)
        ZipOutputStream(jar.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry(ParallelismDetector.CONFIG_FILE))
            zip.write("${ParallelismDetector.PROPERTY}=$enabled\n".toByteArray())
            zip.closeEntry()
        }
        return jar
    }

    private fun configDir(dir: File, enabled: Boolean): File {
        val resources = File(dir, "resources").apply { mkdirs() }
        File(resources, ParallelismDetector.CONFIG_FILE)
            .writeText("${ParallelismDetector.PROPERTY}=$enabled\n")
        return resources
    }

    @Test
    fun `parallelism set as a task system property is found`() {
        val source = ParallelismDetector.detect(mapOf(ParallelismDetector.PROPERTY to "true"))

        assertEquals("${ParallelismDetector.PROPERTY} in the test task's system properties", source)
    }

    @Test
    fun `TestNG's parallel mode and threadCount are found, and its own off switches are not`() {
        // TestNG uses its own options rather than the Jupiter property, so a detector reading only
        // system properties would capture blended records under TestNG.
        assertEquals(
            "TestNG's parallel mode ('methods')",
            ParallelismDetector.detect(emptyMap(), testngParallel = "methods"),
        )
        assertEquals(
            "TestNG's threadCount (4)",
            ParallelismDetector.detect(emptyMap(), testngThreadCount = 4),
        )
        // "none" and a thread count of one are TestNG running serially, which is safe to capture.
        assertNull(ParallelismDetector.detect(emptyMap(), testngParallel = "none"))
        assertNull(ParallelismDetector.detect(emptyMap(), testngParallel = ""))
        assertNull(ParallelismDetector.detect(emptyMap(), testngThreadCount = 1))
    }

    @Test
    fun `parallelism set as a jvm argument is found`() {
        // -D never appears in Test.systemProperties, so a guard reading only that map fails open.
        val source = ParallelismDetector.detect(
            emptyMap(),
            jvmArgs = listOf("-Xmx1g", "-D${ParallelismDetector.PROPERTY}=true"),
        )

        assertTrue(source.orEmpty().contains("JVM argument"), "got: $source")
    }

    @Test
    fun `parallelism set in a properties file on the classpath is found`(@TempDir dir: File) {
        val source = ParallelismDetector.detect(
            emptyMap(),
            testRuntimeFiles = listOf(configDir(dir, enabled = true)),
        )

        assertTrue(source.orEmpty().contains(ParallelismDetector.CONFIG_FILE), "got: $source")
    }

    @Test
    fun `parallelism set inside a jar on the classpath is found`(@TempDir dir: File) {
        // Multi-module builds commonly share junit-platform.properties through a jar.
        val source = ParallelismDetector.detect(
            emptyMap(),
            testRuntimeFiles = listOf(configJar(dir, "config.jar", enabled = true)),
        )

        assertTrue(source.orEmpty().contains("config.jar"), "got: $source")
    }

    @Test
    fun `the routes are named differently so the error points at the right place`(@TempDir dir: File) {
        val fromProperty = ParallelismDetector.detect(mapOf(ParallelismDetector.PROPERTY to "true"))
        val fromArg = ParallelismDetector.detect(
            emptyMap(), jvmArgs = listOf("-D${ParallelismDetector.PROPERTY}=true"),
        )
        val fromFile = ParallelismDetector.detect(
            emptyMap(), testRuntimeFiles = listOf(configDir(dir, enabled = true)),
        )

        assertEquals(3, setOf(fromProperty, fromArg, fromFile).size)
    }

    @Test
    fun `parallelism explicitly disabled is not flagged on any route`(@TempDir dir: File) {
        assertNull(
            ParallelismDetector.detect(
                mapOf(ParallelismDetector.PROPERTY to "false"),
                jvmArgs = listOf("-D${ParallelismDetector.PROPERTY}=false"),
                testRuntimeFiles = listOf(
                    configDir(dir, enabled = false),
                    configJar(dir, "off.jar", enabled = false),
                ),
            )
        )
    }

    @Test
    fun `an absent configuration is not flagged`() {
        assertNull(ParallelismDetector.detect(emptyMap()))
    }

    @Test
    fun `a null system property value is not treated as enabled`() {
        assertNull(ParallelismDetector.detect(mapOf(ParallelismDetector.PROPERTY to null)))
    }

    @Test
    fun `an unrelated jvm argument is not mistaken for the property`() {
        assertNull(
            ParallelismDetector.detect(
                emptyMap(),
                jvmArgs = listOf("-D${ParallelismDetector.PROPERTY}.mode=concurrent"),
            )
        )
    }

    @Test
    fun `a malformed jar does not fail the build`(@TempDir dir: File) {
        // The plugin's job here is to catch a misconfiguration, not to become one.
        val jar = File(dir, "broken.jar").apply { writeText("not a zip") }

        assertNull(ParallelismDetector.detect(emptyMap(), testRuntimeFiles = listOf(jar)))
    }

    @Test
    fun `a jar without the config file is not flagged`(@TempDir dir: File) {
        val jar = File(dir, "plain.jar")
        ZipOutputStream(jar.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("something/else.txt"))
            zip.write("hello".toByteArray())
            zip.closeEntry()
        }

        assertNull(ParallelismDetector.detect(emptyMap(), testRuntimeFiles = listOf(jar)))
    }

    @Test
    fun `an unreadable properties file does not fail the build`(@TempDir dir: File) {
        val resources = File(dir, "resources").apply { mkdirs() }
        File(resources, ParallelismDetector.CONFIG_FILE).mkdirs() // a directory, not a file

        assertNull(ParallelismDetector.detect(emptyMap(), testRuntimeFiles = listOf(resources)))
    }
}
