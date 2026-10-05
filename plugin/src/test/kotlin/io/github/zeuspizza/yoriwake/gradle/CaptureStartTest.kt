package io.github.zeuspizza.yoriwake.gradle

import io.github.zeuspizza.yoriwake.gradle.change.CaptureStart
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

// HEAD and its reflogs where a capture starts, as the test task leaves them for its decode.
class CaptureStartTest {

    private val reading = CaptureStart.Reading("abc123", "1,2,3,4", "absent")

    @Test
    fun `a start reading crosses to the decode intact`() {
        assertEquals(reading, CaptureStart.decode(reading.encode()))
        assertEquals(reading.copy(head = null), CaptureStart.decode(reading.copy(head = null).encode()))

        val pending = assertNotNull(CaptureStart.Pending.decode(CaptureStart.Pending(reading, "s", null).encode()))
        assertEquals(reading, pending.reading)
        assertEquals("s", pending.snapshotId)
        assertNull(pending.statsId)
    }

    @Test
    fun `a start file it did not write reads as no start reading`() {
        // Each refuses, so the decode keeps no stamp and the next run captures.
        listOf(null, "", "abc123", "abc123\n1,2,3,4", "abc123\n\nabsent", "abc123\n1,2,3,4\nabsent\nextra")
            .forEach { assertNull(CaptureStart.decode(it), "decoded ${it?.replace("\n", "|")}") }
        listOf(null, "", reading.encode(), reading.encode() + "\ns", "\n\n\ns\nt")
            .forEach { assertNull(CaptureStart.Pending.decode(it), "decoded ${it?.replace("\n", "|")}") }
    }
}
