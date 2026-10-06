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
