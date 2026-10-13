package io.github.zeuspizza.yoriwake.gradle.wiring

import io.github.zeuspizza.yoriwake.gradle.change.ClasspathFiles
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LastLookTest {

    @Test
    fun `what a comparing action hands over reads back as written`(@TempDir dir: File) {
        val digests = mapOf("app/build/resources/test/a\tb.txt" to "d1", "lib/build/libs/x.jar!/new\nline" to "d2")

        LastLook.write(dir, File("/repo"), listOf("/out/app"), classes = true, digests)
        val read = LastLook.read(LastLook.handOver(dir).readText())!!

        assertEquals(File("/repo").absolutePath, read.rootDir.absolutePath)
        assertEquals(listOf("/out/app"), read.buildDirs)
        assertEquals(true, read.classes)
        assertEquals(digests, read.digests)
    }

    @Test
    fun `a hand-over cut short or garbled reads as nothing`(@TempDir dir: File) {
        LastLook.write(dir, File("/repo"), emptyList(), classes = false, mapOf("a.txt" to "d1"))
        val text = LastLook.handOver(dir).readText()

        assertNull(LastLook.read(text.removeSuffix("end\n")))
        assertNull(LastLook.read("root\t/repo\nclasses\tmaybe\nend\n"))
        assertNull(LastLook.read("classes\tfalse\nend\n"))
        assertNull(LastLook.read("unknown\tx\n" + text))
    }

    @Test
    fun `a hand-over that could not be read forces without walking`() {
        val moved = LastLook.whatMoved(null) { error("walked") }

        assertContains(assertNotNull(moved), "could not be read back")
    }

    @Test
    fun `a walk that is refused or fails forces, naming why`() {
        val compared = LastLook.Compared(File("/repo"), emptyList(), classes = false, mapOf("a.txt" to "d1"))

        assertContains(assertNotNull(LastLook.whatMoved(compared) { ClasspathFiles.Walk.Refused("over budget") }), "over budget")
        assertContains(assertNotNull(LastLook.whatMoved(compared) { throw IOException("disk gone") }), "disk gone")
    }

    @Test
    fun `a classpath file rewritten since it was compared forces, one left alone does not`(@TempDir root: File) {
        val resources = File(root, "app/build/resources/test")
        val info = File(resources, "build-info.properties").apply { parentFile.mkdirs(); writeText("flavor=a\n") }
        val classpath = listOf(resources)
        val walk = { c: LastLook.Compared -> ClasspathFiles.walk(classpath, c.buildDirs, c.rootDir, classes = c.classes) }
        val digests = assertIs<ClasspathFiles.Walk.Found>(ClasspathFiles.walk(classpath, emptyList(), root)).digests
        val compared = LastLook.Compared(root, emptyList(), classes = false, digests)

        assertNull(LastLook.whatMoved(compared, walk))

        info.writeText("flavor=b\n")

        assertContains(assertNotNull(LastLook.whatMoved(compared, walk)), "build-info.properties")
    }
}
