package io.github.zeuspizza.yoriwake.maven

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A distribution's fetch outlasts a slow network and gives up on an archive that is not there; a
 * cached archive is used only when it matches its SHA-512.
 */
class DistributionFetchFunctionalTest {

    private val pauses = mutableListOf<Long>()

    private fun failingFirst(times: Int, failure: () -> IOException): (java.net.URI) -> InputStream {
        var calls = 0
        return {
            calls++
            if (calls <= times) throw failure()
            ByteArrayInputStream("archive".toByteArray())
        }
    }

    @Test
    fun `a fetch that times out twice and then succeeds writes the archive`(@TempDir dir: File) {
        val target = File(dir, "archive.tar.gz")

        DistributionFetch.fetch("3.9.16", target, open = failingFirst(2) { SocketTimeoutException("Read timed out") }, pause = pauses::add)

        assertEquals("archive", target.readText())
        assertEquals(listOf(2_000L, 4_000L), pauses)
    }

    @Test
    fun `a fetch that times out every time fails after the last retry`(@TempDir dir: File) {
        val failure = assertFailsWith<IOException> {
            DistributionFetch.fetch("3.9.16", File(dir, "archive.tar.gz"), open = failingFirst(4) { SocketTimeoutException("Read timed out") }, pause = pauses::add)
        }

        assertIs<SocketTimeoutException>(failure.cause)
        assertEquals(DistributionFetch.PAUSES, pauses)
    }

    @Test
    fun `a missing archive is not retried`(@TempDir dir: File) {
        assertFailsWith<FileNotFoundException> {
            DistributionFetch.fetch("3.9.16", File(dir, "archive.tar.gz"), open = failingFirst(1) { FileNotFoundException("404") }, pause = pauses::add)
        }

        assertEquals(emptyList(), pauses)
    }

    @Test
    fun `a cached archive that does not match its SHA-512 is deleted with its tree and fetched again`(@TempDir dir: File) {
        val archive = File(dir, "archive.tar.gz").apply { writeText("tampered") }
        val home = File(dir, "apache-maven").apply { File(this, "bin").mkdirs() }
        val good = File(dir, "good").apply { writeText("archive") }
        val fetched = mutableListOf<String>()

        DistributionFetch.ensureArchive("3.9.16", archive, home, DistributionFetch.sha512(good)) { version, target ->
            fetched += version
            target.writeText("archive")
        }

        assertEquals(listOf("3.9.16"), fetched)
        assertEquals("archive", archive.readText())
        assertFalse(home.exists())
    }

    @Test
    fun `a cached archive that matches its SHA-512 is kept with its tree`(@TempDir dir: File) {
        val archive = File(dir, "archive.tar.gz").apply { writeText("archive") }
        val home = File(dir, "apache-maven").apply { File(this, "bin").mkdirs() }

        DistributionFetch.ensureArchive("3.9.16", archive, home, DistributionFetch.sha512(archive)) { _, _ -> fail("fetched again") }

        assertTrue(File(home, "bin").isDirectory)
    }

    @Test
    fun `a fetched archive that does not match its SHA-512 is refused`(@TempDir dir: File) {
        val archive = File(dir, "archive.tar.gz")
        val good = File(dir, "good").apply { writeText("archive") }

        assertFailsWith<IllegalStateException> {
            DistributionFetch.ensureArchive("3.9.16", archive, File(dir, "apache-maven"), DistributionFetch.sha512(good)) { _, target ->
                target.writeText("tampered")
            }
        }
        assertFalse(archive.exists())
    }

    @Test
    fun `a cached tree is kept when its archive cannot be fetched again`(@TempDir dir: File) {
        val archive = File(dir, "archive.tar.gz")
        val home = File(dir, "apache-maven").apply { File(this, "bin").mkdirs() }

        assertFailsWith<IOException> {
            DistributionFetch.ensureArchive("3.9.16", archive, home, "0") { _, _ -> throw SocketTimeoutException("Read timed out") }
        }
        assertTrue(File(home, "bin").isDirectory)
    }
}
