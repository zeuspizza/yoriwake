package io.github.zeuspizza.yoriwake.gradle

import io.github.zeuspizza.yoriwake.agent.contract.AgentContract
import io.github.zeuspizza.yoriwake.agent.select.MapReader
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The cause a map is unusable, as an enum rather than prose. `unusableReason()` is written for a
 * person and may be reworded, so callers branch on the enum these tests pin.
 */
class MapReaderReasonTest {

    private fun map(dir: File, version: String?, records: String?) {
        dir.mkdirs()
        records?.let {
            File(dir, AgentContract.COVERAGE_FILE).writeText(it)
            // Every real map carries JVM order; these tests are about the other files.
            File(dir, AgentContract.POSITIONS_FILE).writeText("")
            File(dir, AgentContract.FIRST_TOUCH_FILE).writeText("")
            File(dir, AgentContract.NAMED_TOUCH_FILE).writeText("")
        }
        version?.let { File(dir, AgentContract.MAP_SCHEMA_VERSION_FILE).writeText(it) }
    }

    @Test
    fun `a usable map has no reason at all`(@TempDir dir: File) {
        map(dir, "${AgentContract.MAP_SCHEMA_VERSION}", "SUCCESSFUL\t1\tcom.acme.A\tt\n")

        val result = MapReader.read(dir)

        assertEquals(true, result.isUsable)
        assertNull(result.reason())
    }

    @Test
    fun `no map at all is NO_MAP, not a corruption`(@TempDir dir: File) {
        assertEquals(MapReader.Result.Reason.NO_MAP, MapReader.read(dir).reason())
        assertEquals(MapReader.Result.Reason.NO_MAP, MapReader.read(null as File?).reason())
    }

    @Test
    fun `records without a version marker are a capture that died, not an old format`(@TempDir dir: File) {
        // A capture that dies while decoding leaves records with no version and no scope; calling
        // that an old format points the reader at the wrong cause.
        map(dir, null, "SUCCESSFUL\t1\tcom.acme.A\tt\n")

        assertEquals(MapReader.Result.Reason.CAPTURE_INCOMPLETE, MapReader.read(dir).reason())
    }

    @Test
    fun `a version this build does not read is SCHEMA_MISMATCH`(@TempDir dir: File) {
        map(dir, "999", "SUCCESSFUL\t1\tcom.acme.A\tt\n")

        assertEquals(MapReader.Result.Reason.SCHEMA_MISMATCH, MapReader.read(dir).reason())
    }

    @Test
    fun `a version marker that is not a number is SCHEMA_MISMATCH`(@TempDir dir: File) {
        map(dir, "not-a-number", "SUCCESSFUL\t1\tcom.acme.A\tt\n")

        assertEquals(MapReader.Result.Reason.SCHEMA_MISMATCH, MapReader.read(dir).reason())
    }

    @Test
    fun `a line the parser cannot read is UNREADABLE`(@TempDir dir: File) {
        map(dir, "${AgentContract.MAP_SCHEMA_VERSION}", "this is not a record\n")

        assertEquals(MapReader.Result.Reason.UNREADABLE, MapReader.read(dir).reason())
    }
}
