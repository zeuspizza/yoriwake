package io.github.zeuspizza.yoriwake.agent.select

import io.github.zeuspizza.yoriwake.agent.contract.AgentContract
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Can the selector trust this map? An empty map means "no test covers anything"; an unusable one
 * means "I do not know" and must run everything. Collapsing the two is a silent skip.
 */
class MapReaderTest {

    private fun map(
        dir: File,
        vararg entries: String,
        version: Int? = AgentContract.MAP_SCHEMA_VERSION,
    ): File {
        dir.mkdirs()
        File(dir, "coverage.tsv").writeText(entries.joinToString("\n", postfix = "\n"))
        writeSoloOrder(dir)
        version?.let { File(dir, "schema-version").writeText("$it\n") }
        return dir
    }

    private fun entry(testId: String, outcome: String = "SUCCESSFUL", nanos: Long = 1_000_000, classes: String = "") =
        "$outcome\t$nanos\t$classes\t$testId"

    private fun scope(dir: File, text: String) =
        File(dir, AgentContract.EFFECTIVE_SCOPE_FILE).writeText(text)

    private val recordedScope = listOf("yoriwake-effective-scope 1", "include\tcom.acme.*").joinToString("\n")

    @Test
    fun `a capture that died partway is not reported as an old map`(@TempDir dir: File) {
        // Records on disk without the metadata a completed capture writes last mean a crashed
        // capture; calling it an old map would send the reader down the wrong path.
        map(dir, entry("[class:A]/[method:a()]"), version = null)
        File(dir, "raw").mkdirs()

        val result = MapReader.read(dir)

        assertFalse(result.isUsable())
        assertContains(result.unusableReason(), "was never finished")
        assertContains(result.unusableReason(), "could not update the map")
        assertFalse(
            result.unusableReason().contains("predates format versioning"),
            "a crashed capture must not be blamed on format age: ${result.unusableReason()}",
        )
    }

    @Test
    fun `a genuinely old map is still reported as one`(@TempDir dir: File) {
        // No records and no version: nothing here says a capture ever ran, so format age remains
        // the honest reading. The two messages must not collapse into one.
        dir.mkdirs()

        val result = MapReader.read(dir)

        assertFalse(result.isUsable())
        assertFalse(
            result.unusableReason().contains("was never finished"),
            "an empty directory is not a crashed capture: ${result.unusableReason()}",
        )
    }

    @Test
    fun `a map that names the scope its records were captured under says so`(@TempDir dir: File) {
        map(dir, entry("[class:A]/[method:a()]"))
        scope(dir, recordedScope + "\n")

        val result = MapReader.read(dir)

        assertEquals(MapReader.ScopeProvenance.RECORDED, result.scopeProvenance())
        assertEquals(MapReader.ScopeProvenance.RECORDED, MapReader.scopeProvenanceOf(dir))
        assertEquals(recordedScope, result.effectiveScope())
    }

    @Test
    fun `a scope unlike the build's current one is still the scope these records were captured under`(
        @TempDir dir: File,
    ) {
        // The question is whether a probe would have fired when these records were captured, not
        // whether the recorded scope matches the live configuration, which diverges as soon as a
        // package is added.
        map(dir, entry("[class:A]/[method:a()]"))
        scope(dir, "yoriwake-effective-scope 1\ninclude\torg.elsewhere.*\n")

        assertEquals(MapReader.ScopeProvenance.RECORDED, MapReader.read(dir).scopeProvenance())
    }

    @Test
    fun `a map that records no scope is unknown, and stays unknown`(@TempDir dir: File) {
        map(dir, entry("[class:A]/[method:a()]"))

        assertEquals(MapReader.ScopeProvenance.UNKNOWN, MapReader.read(dir).scopeProvenance())
        assertEquals(MapReader.ScopeProvenance.UNKNOWN, MapReader.scopeProvenanceOf(dir))
        assertEquals(null, MapReader.recordedScopeOf(dir))
    }

    @Test
    fun `an empty scope file is unknown rather than a scope that watched nothing`(@TempDir dir: File) {
        map(dir, entry("[class:A]/[method:a()]"))
        scope(dir, "\n")

        val result = MapReader.read(dir)

        assertEquals(null, result.effectiveScope())
        assertEquals(MapReader.ScopeProvenance.UNKNOWN, result.scopeProvenance())
    }

    @Test
    fun `reads a test's outcome, duration and covered classes`(@TempDir dir: File) {
        map(dir, entry("[class:A]/[method:a()]", nanos = 5_000_000, classes = "com.acme.A,com.acme.B"))

        val entry = MapReader.read(dir).entries().single()

        assertEquals("[class:A]/[method:a()]", entry.testId())
        assertEquals("SUCCESSFUL", entry.outcome())
        assertEquals(5_000_000, entry.durationNanos())
        assertEquals(setOf("com.acme.A", "com.acme.B"), entry.coveredClasses())
    }

    @Test
    fun `a test covering nothing is a usable entry, not an unusable map`(@TempDir dir: File) {
        // "Covered nothing" and "we do not know what it covered" mean opposite things to a selector.
        map(dir, entry("[class:A]/[method:a()]", classes = ""))

        val result = MapReader.read(dir)

        assertTrue(result.isUsable)
        assertTrue(result.entries().single().coveredClasses().isEmpty())
    }

    @Test
    fun `a test id containing tabs does not corrupt the following fields`(@TempDir dir: File) {
        // The id is the only field with arbitrary content; the decoder writes it escaped.
        val id = "weird\tid\\with\nbreaks"
        map(dir, entry(io.github.zeuspizza.yoriwake.agent.contract.Tsv.escape(id), classes = "com.acme.A"))

        val entry = MapReader.read(dir).entries().single()

        assertEquals(setOf("com.acme.A"), entry.coveredClasses())
        assertEquals(id, entry.testId())
    }

    @Test
    fun `an unescaped tab in a record makes the map unusable rather than misread`(@TempDir dir: File) {
        map(dir, entry("weird\tid", classes = "com.acme.A"))

        assertFalse(MapReader.read(dir).isUsable)
    }

    @Test
    fun `an absent map is unusable and says a run will build one`(@TempDir dir: File) {
        val result = MapReader.read(File(dir, "never-created"))

        assertFalse(result.isUsable)
        assertContains(result.unusableReason(), "no map at")
    }

    @Test
    fun `a null map directory is unusable rather than a crash`() {
        assertFalse(MapReader.read(null).isUsable)
    }

    @Test
    fun `an empty map is unusable, not a map covering nothing`(@TempDir dir: File) {
        map(dir)

        val result = MapReader.read(dir)

        assertFalse(result.isUsable)
        assertContains(result.unusableReason(), "empty")
    }

    @Test
    fun `a map from a different schema version is refused by version, not by parse error`(
        @TempDir dir: File,
    ) {
        // Without a version marker, an old capture yields a message naming neither cause nor remedy.
        map(dir, entry("[class:A]/[method:a()]"), version = AgentContract.MAP_SCHEMA_VERSION + 1)

        val result = MapReader.read(dir)

        assertFalse(result.isUsable)
        assertContains(result.unusableReason(), "schema version")
        assertContains(result.unusableReason(), "rebuild")
    }

    @Test
    fun `a map with no version file is refused`(@TempDir dir: File) {
        // The refusal is the safety property; records with no version mean a capture died before
        // writing its metadata, so the message says that rather than "old map".
        map(dir, entry("[class:A]/[method:a()]"), version = null)

        val result = MapReader.read(dir)

        assertFalse(result.isUsable)
        assertContains(result.unusableReason(), "no schema-version")
    }

    @Test
    fun `an unreadable version is refused rather than assumed current`(@TempDir dir: File) {
        map(dir, entry("[class:A]/[method:a()]"))
        File(dir, "schema-version").writeText("not-a-number\n")

        assertFalse(MapReader.read(dir).isUsable)
    }

    @Test
    fun `the version is checked before the content`(@TempDir dir: File) {
        // A wrong-version map is very likely to also fail parsing. Reporting the parse error would
        // send the reader looking for corruption instead of a format change.
        map(dir, "this line is not a valid entry", version = AgentContract.MAP_SCHEMA_VERSION + 1)

        assertContains(MapReader.read(dir).unusableReason(), "schema version")
    }

    @Test
    fun `a malformed entry makes the whole map unusable`(@TempDir dir: File) {
        // Skipping the bad line would silently drop a test from the map, and a test absent from the
        // map is a test that can never be selected.
        map(dir, entry("[class:A]/[method:a()]"), "only\ttwo")

        val result = MapReader.read(dir)

        assertFalse(result.isUsable)
        assertContains(result.unusableReason(), "line 2")
    }

    @Test
    fun `a non-numeric duration makes the map unusable`(@TempDir dir: File) {
        map(dir, "SUCCESSFUL\tnot-a-number\t\t[class:A]")

        assertFalse(MapReader.read(dir).isUsable)
    }

    @Test
    fun `blank lines are ignored rather than treated as malformed`(@TempDir dir: File) {
        dir.mkdirs()
        File(dir, "coverage.tsv").writeText(entry("[class:A]/[method:a()]") + "\n\n")
        writeSoloOrder(dir)
        File(dir, "schema-version").writeText("${AgentContract.MAP_SCHEMA_VERSION}\n")

        assertEquals(1, MapReader.read(dir).entries().size)
    }

    @Test
    fun `many entries are read in order`(@TempDir dir: File) {
        map(dir, *(1..500).map { entry("[class:S]/[method:t$it()]", classes = "com.acme.C$it") }.toTypedArray())

        val entries = MapReader.read(dir).entries()

        assertEquals(500, entries.size)
        assertContains(entries.first().testId(), "t1()")
    }
}
