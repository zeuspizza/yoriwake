package io.github.zeuspizza.yoriwake.gradle.capture

import io.github.zeuspizza.yoriwake.agent.contract.AgentContract
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

class MapProvenanceTest {

    private fun map(dir: File): File {
        File(dir, AgentContract.COVERAGE_FILE).writeText("SUCCESSFUL\t1\tcom.acme.A\t[class:A]/[method:a()]\n")
        File(dir, AgentContract.MAP_SCHEMA_VERSION_FILE).writeText("7\n")
        return dir
    }

    @Test
    fun `an edited coverage file changes the digest`(@TempDir dir: File) {
        val before = MapProvenance.digest(map(dir))

        File(dir, AgentContract.COVERAGE_FILE).appendText("\n")

        assertNotEquals(before, MapProvenance.digest(dir))
    }

    @Test
    fun `files outside the digested list do not move the digest`(@TempDir dir: File) {
        val before = MapProvenance.digest(map(dir))

        File(dir, AgentContract.DECISIONS_FILE).writeText("anything")
        File(dir, AgentContract.MAP_DIGEST_FILE).writeText("sha256 ${"0".repeat(64)}\n")

        assertEquals(before, MapProvenance.digest(dir))
    }

    @Test
    fun `an absent file and an empty one digest differently`(@TempDir dir: File) {
        val absent = MapProvenance.digest(map(dir))

        File(dir, AgentContract.LOADED_FILE).writeText("")

        assertNotEquals(absent, MapProvenance.digest(dir))
    }

    @Test
    fun `the verdict follows the listed digest`(@TempDir dir: File) {
        val digest = MapProvenance.digest(map(dir))

        assertEquals(MapProvenance.Verdict.Trusted(digest), MapProvenance.verify(dir, digest))
        assertIs<MapProvenance.Verdict.Untrusted>(MapProvenance.verify(dir, "f".repeat(64)))
        assertIs<MapProvenance.Verdict.Unverified>(MapProvenance.verify(dir, null))
    }

    // Computed outside this code, by the digest's definition over each release's file list.
    private val releasedDigest = "638c298d36cfd92afc529aca0b0b635542d09b5912f8aa190b3a0cdf340b02ef"
    private val currentDigest = "965cdb082c9b16416f782c73b99086925df47091ee6bbc0eac7353c6d06dde73"

    @Test
    fun `each digest scheme gives the digest its release wrote`(@TempDir dir: File) {
        map(dir)

        assertEquals(releasedDigest, MapProvenance.digest(dir, MapProvenance.EARLIER.single { it.releases == "0.2.0" }.files))
        assertEquals(
            currentDigest, MapProvenance.digest(dir),
            "the digested files changed: freeze the last released list in MapProvenance.EARLIER, so a " +
                "trusted-map list that release wrote is named as such, then pin the new digest here",
        )
    }

    @Test
    fun `a list an earlier release wrote is named as such, and still not trusted`(@TempDir dir: File) {
        map(dir)

        val verdict = assertIs<MapProvenance.Verdict.Untrusted>(MapProvenance.verify(dir, releasedDigest))
        assertContains(verdict.reason, "written by yoriwake 0.2.0")
        assertContains(verdict.reason, "covers a different set of files")
        assertContains(verdict.reason, "the default branch's next run on this version")
    }

    @Test
    fun `a file only this version digests, changed under an earlier list, is still not trusted`(@TempDir dir: File) {
        map(dir)
        File(dir, AgentContract.ENVIRONMENT_FILE).writeText("PATH\t1\n")

        val verdict = assertIs<MapProvenance.Verdict.Untrusted>(MapProvenance.verify(dir, releasedDigest))
        assertContains(verdict.reason, "written by yoriwake 0.2.0")
    }

    @Test
    fun `a digest no release computed keeps the plain reason`(@TempDir dir: File) {
        map(dir)

        val verdict = assertIs<MapProvenance.Verdict.Untrusted>(MapProvenance.verify(dir, "f".repeat(64)))
        assertContains(verdict.reason, "the map is not what the trusted-map list vouches for")
    }

    @Test
    fun `a trusted-map list is read line by line, and a malformed line names itself`() {
        val digest = "a".repeat(64)

        assertEquals(mapOf("test-1" to digest), MapProvenance.parseTrustedList("test-1\t$digest\n\n", "flag"))
        assertEquals(emptyMap(), MapProvenance.parseTrustedList("", "flag"))
        val malformed = assertThrows<IllegalArgumentException> {
            MapProvenance.parseTrustedList("test-1 $digest\n", "flag")
        }
        assertContains(malformed.message.orEmpty(), "\"test-1 $digest\"")
    }
}
