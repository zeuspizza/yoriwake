package io.github.zeuspizza.yoriwake.agent.capture

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.URL
import java.util.Collections
import java.util.Enumeration
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * netty-tcnative's BoringSSL build is listed and its OpenSSL build is not, and both ship libraries
 * of the same names: the loaded file counts only when its bytes are a resource of the jar that
 * declares the BoringSSL artifact at a version reviewed.
 */
class JarIdentityTest {

    private val resource = "libnetty_tcnative_osx_aarch_64.jnilib"
    private val library = byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), 1, 2, 3)
    private val boringssl = "netty-tcnative-boringssl-static"

    /** A jar holding [bytes] as Netty's native resource and one pom.properties per entry of [declares]. */
    private fun jar(dir: File, bytes: ByteArray = library, vararg declares: Pair<String, String> = arrayOf(boringssl to "2.0.84.Final")): File {
        val jar = File(dir, "tcnative-${declares.joinToString("-") { it.first }}.jar")
        ZipOutputStream(jar.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("META-INF/native/$resource"))
            zip.write(bytes)
            for ((artifact, version) in declares) {
                zip.putNextEntry(ZipEntry("META-INF/maven/io.netty/$artifact/pom.properties"))
                zip.write("groupId=io.netty\nartifactId=$artifact\nversion=$version\n".toByteArray())
            }
        }
        return jar
    }

    private fun loaded(dir: File, name: String = resource, bytes: ByteArray = library) =
        File(dir, name).apply { writeBytes(bytes) }

    private fun unmet(
        loaded: String,
        jar: File?,
        environment: (String) -> String? = { null },
        url: (File) -> URL = { java.net.URI.create("jar:" + it.toURI() + "!/META-INF/native/$resource").toURL() },
    ): String? = JarIdentity.unmet(
        JarIdentityTest::class.java, loaded, "io.netty", boringssl, arrayOf("2.0.84.Final"),
        { environment(it) },
        { name: String ->
            if (jar != null && name == "META-INF/native/$resource") Collections.enumeration(listOf(url(jar)))
            else Collections.emptyEnumeration<URL>()
        },
    )

    @Test
    fun `the loaded file equal to the BoringSSL jar's resource is identified, extracted digits and all`(@TempDir dir: File) {
        val jar = jar(dir)
        assertNull(unmet(loaded(dir).path, jar))
        assertNull(unmet(loaded(dir, "libnetty_tcnative_osx_aarch_648123456789.jnilib").path, jar))
    }

    @Test
    fun `the same bytes in a jar of the OpenSSL build or of two artifacts are not identified`(@TempDir dir: File) {
        val file = loaded(dir).path
        assertEquals("tcnative-netty-tcnative.jar is netty-tcnative, not $boringssl",
            unmet(file, jar(dir, library, "netty-tcnative" to "2.0.84.Final")))
        assertContains(unmet(file, jar(dir, library, boringssl to "2.0.84.Final", "netty-tcnative" to "2.0.84.Final"))!!,
            "declares more than one artifact of io.netty")
        assertEquals("$boringssl 2.0.83.Final is not a version reviewed",
            unmet(file, jar(dir, library, boringssl to "2.0.83.Final")))
        assertContains(unmet(file, jar(dir, library, "$boringssl-fips" to "2.0.84.Final"))!!, "not $boringssl")
    }

    @Test
    fun `a library whose bytes differ, or that no jar holds, is not identified`(@TempDir dir: File) {
        val jar = jar(dir)
        // Same length, so only the bytes themselves tell them apart.
        assertContains(unmet(loaded(dir, bytes = library.copyOf().also { it[4] = 9 }).path, jar)!!, "no resource a jar beside")
        assertContains(unmet(loaded(dir, bytes = library + 0).path, jar)!!, "no resource a jar beside")
        assertContains(unmet(loaded(dir).path, null)!!, "no resource a jar beside")
        assertContains(unmet(loaded(dir).path, jar, url = { File(dir, "META-INF/native/$resource").toURI().toURL() })!!,
            "is not in a jar")
    }

    @Test
    fun `a file that cannot be read or is not known is not identified`(@TempDir dir: File) {
        val gone = loaded(dir).apply { delete() }
        assertContains(unmet(gone.path, jar(dir))!!, "no resource a jar beside")
        assertContains(unmet("netty_tcnative", jar(dir))!!, "is not known")
    }

    @Test
    fun `on macOS a set DYLD variable leaves the library unidentified`(@TempDir dir: File) {
        assumeTrue(System.getProperty("os.name").startsWith("Mac"), "DYLD variables act on macOS only")
        val why = unmet(loaded(dir).path, jar(dir), environment = { if (it == "DYLD_INSERT_LIBRARIES") "/x.dylib" else null })
        assertEquals("DYLD_INSERT_LIBRARIES is set, which can load another library than the file compared", why)
    }

    @Test
    fun `the names tried run back over the digits an extracted copy adds, and no further`() {
        assertEquals(
            listOf("netty_tcnative_osx_aarch_6481.so", "netty_tcnative_osx_aarch_6481.dylib", "netty_tcnative_osx_aarch_6481.jnilib",
                "netty_tcnative_osx_aarch_648.so", "netty_tcnative_osx_aarch_648.dylib", "netty_tcnative_osx_aarch_648.jnilib",
                "netty_tcnative_osx_aarch_64.so", "netty_tcnative_osx_aarch_64.dylib", "netty_tcnative_osx_aarch_64.jnilib",
                "netty_tcnative_osx_aarch_6.so", "netty_tcnative_osx_aarch_6.dylib", "netty_tcnative_osx_aarch_6.jnilib",
                "netty_tcnative_osx_aarch_.so", "netty_tcnative_osx_aarch_.dylib", "netty_tcnative_osx_aarch_.jnilib"),
            JarIdentity.candidates("netty_tcnative_osx_aarch_6481.jnilib"),
        )
    }
}
