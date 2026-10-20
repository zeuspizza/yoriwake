package io.github.zeuspizza.yoriwake.gradle.capture

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class JvmArgFilesTest {

    /** Each file's arguments as the `java` launcher of JDK 11, 21, 25 and 27 reads them. */
    @Test
    fun `an argument file splits as the java launcher splits it`() {
        val cases = mapOf(
            "-Dp.a=1 -Dp.b=2\n-Dp.c=3\n" to listOf("-Dp.a=1", "-Dp.b=2", "-Dp.c=3"),
            "-Dp.a=\"x y\"\n" to listOf("-Dp.a=x y"),
            "-Dp.a='x y'\n" to listOf("-Dp.a=x y"),
            "-Dp.a=pre\"x y\"post\n" to listOf("-Dp.a=prex ypost"),
            "\"-Dp.a=1\"\n" to listOf("-Dp.a=1"),
            "-Dp.a=\"it's\"\n" to listOf("-Dp.a=it's"),
            "-Dp.a=\"\" -Dp.b=2\n" to listOf("-Dp.a=", "-Dp.b=2"),
            "# -Dp.a=1\n-Dp.b=2\n" to listOf("-Dp.b=2"),
            "-Dp.a=x #y -Dp.b=2\n-Dp.c=3\n" to listOf("-Dp.a=x", "-Dp.c=3"),
            "-Dp.a=\"x#y\"\n" to listOf("-Dp.a=x#y"),
            "-Dp.a=x\\ty\n" to listOf("-Dp.a=x\\ty"),
            "-Dp.a=\"x\\ty\\\\z\\\"w\"\n" to listOf("-Dp.a=x\ty\\z\"w"),
            "-Dp.a=\"x\\qy\"\n" to listOf("-Dp.a=xqy"),
            "-Dp.a=\"one\\\n    two\"\n" to listOf("-Dp.a=onetwo"),
            "-Dp.a=\"one\\\r\n  #two\"\n" to listOf("-Dp.a=one#two"),
            "-Dp.a=one\\\n    two\n" to listOf("-Dp.a=one\\", "two"),
            "-Dp.a=\"one\ntwo\"\n" to listOf("-Dp.a=one", "two"),
            "-Dp.a=\"one two\n" to listOf("-Dp.a=one two"),
            "-Dp.a=1\r\n-Dp.b=2\r\n" to listOf("-Dp.a=1", "-Dp.b=2"),
            "-Dp.a=1\t-Dp.b=2\u000c-Dp.c=3\n" to listOf("-Dp.a=1", "-Dp.b=2", "-Dp.c=3"),
            "-Dp.a=1 @nested.args\n" to listOf("-Dp.a=1", "@nested.args"),
        )
        for ((text, expected) in cases) {
            assertEquals(expected, JvmArgFiles.tokens(text), text)
        }
    }

    @Test
    fun `an argument cut short by a comment is kept, as the newest launcher keeps it`() {
        // JDK 25 and earlier drop it; comparing one property more only ever runs more.
        assertEquals(listOf("-Dp.a=x"), JvmArgFiles.tokens("-Dp.a=x#y -Dp.b=2\n"))
    }

    @Test
    fun `argument files are expanded in place, a relative one from the working directory`(@TempDir dir: File) {
        File(dir, "test.args").writeText("-Dtree.state=clean\n")
        val absolute = File(dir, "abs.args").apply { writeText("-Dother=1\n") }

        val expanded = JvmArgFiles.expand(listOf("-Xmx1g", "@test.args", "@" + absolute.path, "-Dlast=2"), dir)

        assertEquals(listOf("-Xmx1g", "-Dtree.state=clean", "-Dother=1", "-Dlast=2"), expanded)
    }

    @Test
    fun `an escaped at, a lone at and anything after disabling expansion are kept as they are`(@TempDir dir: File) {
        File(dir, "test.args").writeText("-Dtree.state=clean\n")

        val expanded = JvmArgFiles.expand(listOf("@@test.args", "@", "--disable-@files", "@test.args"), dir)

        assertEquals(listOf("@test.args", "@", "--disable-@files", "@test.args"), expanded)
    }

    @Test
    fun `an argument file that cannot be read matches no other run`(@TempDir dir: File) {
        val first = JvmArgFiles.expand(listOf("@missing.args"), dir)
        val second = JvmArgFiles.expand(listOf("@missing.args"), dir)

        assertTrue(first.single().startsWith("-D@missing.args="), first.toString())
        assertNotEquals(first, second)
    }
}
