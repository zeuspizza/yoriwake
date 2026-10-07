package io.github.zeuspizza.yoriwake.gradle.change

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ClasspathFilesTest {

    private fun jar(target: File, time: Long, vararg entries: Pair<String, String>): File {
        target.parentFile.mkdirs()
        ZipOutputStream(target.outputStream()).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name).apply { this.time = time })
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return target
    }

    private fun file(root: File, path: String, content: String) =
        File(root, path).apply { parentFile.mkdirs(); writeText(content) }

    private fun found(walk: ClasspathFiles.Walk) = assertIs<ClasspathFiles.Walk.Found>(walk).digests

    @Test
    fun `a resource directory and an own jar give a row per non-class file, the jar per entry`(@TempDir root: File) {
        val resources = File(root, "app/build/resources/test")
        file(resources, "info.properties", "info=a\n")
        file(resources, "nested/data.json", "{}")
        file(resources, "dev/sample/Ignored.class", "bytes")
        val lib = jar(
            File(root, "lib/build/libs/lib.jar"), 0L,
            "META-INF/MANIFEST.MF" to "Build-Revision: r1\n",
            "dev/lib/Lib.class" to "bytes",
        )

        val digests = found(ClasspathFiles.walk(listOf(resources, lib), listOf(), root))

        assertEquals(
            setOf(
                "app/build/resources/test/info.properties",
                "app/build/resources/test/nested/data.json",
                "lib/build/libs/lib.jar!/META-INF/MANIFEST.MF",
            ),
            digests.keys,
        )
    }

    @Test
    fun `a dependency jar from outside the root gives no rows`(@TempDir dir: File) {
        val root = File(dir, "project").apply { mkdirs() }
        val dependency = jar(File(dir, "cache/dep.jar"), 0L, "META-INF/MANIFEST.MF" to "x\n")

        assertEquals(emptyMap(), found(ClasspathFiles.walk(listOf(dependency), listOf(), root)))
    }

    @Test
    fun `a module whose build directory lies outside the root still gives rows`(@TempDir dir: File) {
        val root = File(dir, "project").apply { mkdirs() }
        val buildDir = File(dir, "out/app")
        val resources = File(buildDir, "resources/test")
        file(resources, "info.properties", "info=a\n")

        val digests = found(ClasspathFiles.walk(listOf(resources), listOf(buildDir.path), root))

        assertEquals(setOf("../out/app/resources/test/info.properties"), digests.keys)
    }

    @Test
    fun `Gradle's state under the root is not build output`(@TempDir root: File) {
        val agent = jar(File(root, ".gradle/yoriwake-agent/agent.jar"), 0L, "META-INF/MANIFEST.MF" to "x\n")

        assertEquals(emptyMap(), found(ClasspathFiles.walk(listOf(agent), listOf(), root)))
    }

    @Test
    fun `a jar rebuilt with the same entries and new timestamps gives the same rows`(@TempDir root: File) {
        val lib = File(root, "lib/build/libs/lib.jar")
        jar(lib, 0L, "META-INF/MANIFEST.MF" to "Build-Revision: r1\n", "a.txt" to "a")
        val before = found(ClasspathFiles.walk(listOf(lib), listOf(), root))

        jar(lib, 1_700_000_000_000L, "META-INF/MANIFEST.MF" to "Build-Revision: r1\n", "a.txt" to "a")

        assertEquals(before, found(ClasspathFiles.walk(listOf(lib), listOf(), root)))
    }

    @Test
    fun `a walk over its file budget refuses rather than recording part of the files`(@TempDir root: File) {
        val resources = File(root, "build/resources/test")
        repeat(3) { file(resources, "r$it.txt", "$it") }

        assertIs<ClasspathFiles.Walk.Refused>(ClasspathFiles.walk(listOf(resources), listOf(), root, maxFiles = 2))
    }

    @Test
    fun `a jar that cannot be opened moves as a whole`(@TempDir root: File) {
        val broken = file(root, "lib/build/libs/lib.jar", "not a zip")

        assertEquals(
            mapOf("lib/build/libs/lib.jar" to ClasspathFiles.UNREADABLE),
            found(ClasspathFiles.walk(listOf(broken), listOf(), root)),
        )
    }

    @Test
    fun `a changed, an appeared and a gone file all count as moved`() {
        val moved = ClasspathFiles.compare(
            recorded = mapOf("a" to "1", "b" to "2", "c" to "3"),
            current = mapOf("a" to "1", "b" to "changed", "d" to "4"),
        )

        assertEquals(mapOf("b" to "changed", "c" to "gone", "d" to "appeared"), moved.files)
    }

    @Test
    fun `a file unreadable at either end counts as moved, even unreadable at both`() {
        val unreadable = ClasspathFiles.UNREADABLE

        val moved = ClasspathFiles.compare(
            recorded = mapOf("now" to "1", "then" to unreadable, "both" to unreadable),
            current = mapOf("now" to unreadable, "then" to "1", "both" to unreadable),
        )

        assertEquals(setOf("now", "then", "both"), moved.files.keys)
    }

    @Test
    fun `nothing moved when every digest matches`() {
        assertTrue(ClasspathFiles.compare(mapOf("a" to "1"), mapOf("a" to "1")).isEmpty)
    }

    @Test
    fun `seven moved files name five and count the rest`() {
        val moved = ClasspathFiles.compare(emptyMap(), (1..7).associate { "f$it" to "$it" })

        assertEquals(
            "f1 (appeared), f2 (appeared), f3 (appeared), f4 (appeared), f5 (appeared) and 2 more",
            moved.named(),
        )
    }
}
