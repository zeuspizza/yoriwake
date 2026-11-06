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
import kotlin.test.assertIs

/** A distribution's fetch outlasts a slow network and gives up on an archive that is not there. */
class DistributionFetchTest {

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
}
