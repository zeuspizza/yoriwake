package io.github.zeuspizza.yoriwake.maven

import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.net.URI

/**
 * Downloads a Maven distribution's archive from Maven Central, retrying a fetch the network cut
 * short: the suite fetches every distribution of the matrix, and one slow response failed it whole.
 */
internal object DistributionFetch {

    /** The pause before each retry: three retries, about fourteen seconds in all. */
    val PAUSES = listOf(2_000L, 4_000L, 8_000L)

    /** Where Central keeps [version]'s archive; the same file Apache's own archive host serves. */
    fun uri(version: String): URI =
        URI("https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/$version/apache-maven-$version-bin.tar.gz")

    /**
     * Writes [version]'s archive to [target], retrying after each pause of [pauses] when a fetch
     * fails on the network. A missing archive is not retried: it will not appear by waiting.
     */
    fun fetch(
        version: String,
        target: File,
        pauses: List<Long> = PAUSES,
        open: (URI) -> InputStream = ::openStream,
        pause: (Long) -> Unit = Thread::sleep,
    ) {
        val uri = uri(version)
        for (attempt in 0..pauses.size) {
            try {
                open(uri).use { input -> target.outputStream().use { input.copyTo(it) } }
                return
            } catch (missing: FileNotFoundException) {
                throw missing
            } catch (failed: IOException) {
                if (attempt == pauses.size) throw IOException("Maven $version: ${pauses.size + 1} fetches of $uri failed", failed)
                pause(pauses[attempt])
            }
        }
    }

    private fun openStream(uri: URI): InputStream =
        uri.toURL().openConnection().apply { connectTimeout = 30_000; readTimeout = 120_000 }.getInputStream()
}
