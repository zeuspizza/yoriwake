package io.github.zeuspizza.yoriwake.gradle.breaking

import io.github.zeuspizza.yoriwake.agent.contract.AgentContract
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Attempts on a restored map nobody vouched for. On a run given a trusted-map list, a map narrows
 * only when the list names its directory with its exact digest.
 */
class WhoWroteTheMapAttempts : BreakAttempt() {

    private fun source(path: String, body: String) = path to body.trimIndent()

    private val codec = source(
        "src/main/java/dev/sample/Codec.java",
        "package dev.sample;\npublic class Codec { public String encode(String s) { return new StringBuilder(s).reverse().toString(); } }",
    )

    private val other = source(
        "src/main/java/dev/sample/Other.java",
        "package dev.sample;\npublic class Other { public int one() { return 1; } }",
    )

    private val first = source(
        "src/test/java/dev/sample/A0UnrelatedTest.java",
        """
        package dev.sample;
        import org.junit.jupiter.api.Test;
        import static org.junit.jupiter.api.Assertions.assertEquals;
        class A0UnrelatedTest { @Test void runsFirst() { assertEquals(1, new Other().one()); } }
        """,
    )

    private val codecTest = source(
        "src/test/java/dev/sample/A9CodecTest.java",
        """
        package dev.sample;
        import org.junit.jupiter.api.Test;
        import static org.junit.jupiter.api.Assertions.assertEquals;
        class A9CodecTest { @Test void reverses() { assertEquals("cba", new Codec().encode("abc")); } }
        """,
    )

    /** The list is given in the committed gradle.properties, since the selecting run's arguments are fixed. */
    private val suite = listOf(
        codec, other, first, codecTest, classOrderByName,
        "gradle.properties" to "yoriwake.trustedMaps=build/trusted.tsv\n",
    )

    private fun changeCodec(dir: File) {
        val file = File(dir, codec.first)
        file.writeText(file.readText().replace("reverse()", "reverse().append(\"!\")"))
    }

    private fun mapDir(dir: File): File =
        File(dir, ".gradle/yoriwake").listFiles().orEmpty().single(File::isDirectory)

    /** The digest the recording run wrote for the map, as a trusted run would have listed it. */
    private fun recordedDigest(mapDir: File): String =
        File(mapDir, AgentContract.MAP_DIGEST_FILE).readText().trim().removePrefix("sha256 ")

    private fun head(dir: File): String =
        ProcessBuilder("git", "rev-parse", "HEAD").directory(dir).start()
            .let { process -> process.inputStream.bufferedReader().readText().trim().also { process.waitFor() } }

    // Track: code reading. Boundary: MapProvenance.DIGESTED, the files the digest covers, against the
    // map files the selecting run reads. The most useful lie a restored map can tell is its capture
    // stamp: moved to HEAD, it switches off the widening that names a committed change. The list
    // vouches for the map as recorded, so the moved stamp must be caught by the digest.
    @Test
    fun `a map whose capture stamp was moved to HEAD is not the map the list vouches for`(@TempDir dir: File) {
        val result = attempt(dir, "dev.sample.A9CodecTest", Aim.Forced("map-untrusted"), suite) {
            val map = mapDir(it)
            File(it, "build/trusted.tsv").writeText("${map.name}\t${recordedDigest(map)}\n")
            changeCodec(it)
            commit(it, "change Codec")
            File(map, "capture-commit").writeText(head(it) + "\n")
            File(it, "scratch.txt").writeText("scratch")
            git(it, "add", "scratch.txt")
            File(it, "scratch.txt").delete()
        }

        assertOutcome(BreakAttempt.Outcome.HELD_BY_FORCE, result)
    }

    // Track: code reading. Boundary: MapProvenance.verify looks the map up by its directory name.
    // A list naming the map's exact digest under another directory (another task's or build's
    // map) does not vouch for this one.
    @Test
    fun `a list that names the map's digest under another directory does not vouch for it`(@TempDir dir: File) {
        val result = attempt(dir, "dev.sample.A9CodecTest", Aim.Forced("map-unverified"), suite) {
            val map = mapDir(it)
            File(it, "build/trusted.tsv").writeText("${map.name}-other\t${recordedDigest(map)}\n")
            changeCodec(it)
        }

        assertOutcome(BreakAttempt.Outcome.HELD_BY_FORCE, result)
    }

    // Track: code reading. Boundary: MapProvenance.DIGESTED again, for the files this release added
    // beside the map: which classpath files each test JVM read, and the environment it ran in. Each
    // decides what runs, so a restored map that drops one must not pass for the map the list
    // vouches for. Here the record of classpath-file reads is removed, or emptied where it held any.
    @Test
    fun `a map whose record of classpath-file reads was removed is not the map the list vouches for`(@TempDir dir: File) {
        val result = attempt(dir, "dev.sample.A9CodecTest", Aim.Forced("map-untrusted"), suite) {
            val map = mapDir(it)
            File(it, "build/trusted.tsv").writeText("${map.name}\t${recordedDigest(map)}\n")
            val reads = File(map, AgentContract.RESOURCE_TOUCH_FILE)
            check(reads.isFile) { "the capture wrote no ${reads.name}" }
            if (reads.length() > 0) reads.writeText("") else reads.delete()
            changeCodec(it)
        }

        assertOutcome(BreakAttempt.Outcome.HELD_BY_FORCE, result)
    }

    // Track: fixtures. Row: MP2. An empty list is valid, and names no map.
    @Test
    fun `an empty trusted list vouches for no map`(@TempDir dir: File) {
        val result = attempt(dir, "dev.sample.A9CodecTest", Aim.Forced("map-unverified"), suite) {
            File(it, "build/trusted.tsv").writeText("")
            changeCodec(it)
        }

        assertOutcome(BreakAttempt.Outcome.HELD_BY_FORCE, result)
    }
}
