package io.github.zeuspizza.yoriwake.gradle

import io.github.zeuspizza.yoriwake.gradle.capture.Schedule
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Duration
import java.time.Instant
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** When a map has gone long enough without a full run that the next selecting run runs everything. */
class ScheduleTest {

    private val now = Instant.parse("2026-10-15T10:00:00Z")

    private fun due(count: String? = "0", time: String? = now.toString(), builds: Int? = null, days: Int? = null) =
        Schedule.due(mapExists = true, count, time, captureCommit = "1a2b3c4d5e6f7a8b", now, builds, days)

    @Test
    fun `the Nth build after a full run is due, the one before is not`() {
        assertNull(due(count = "1", builds = 3))
        val reason = assertNotNull(due(count = "2", builds = 3))
        assertContains(reason, "3 builds since the last full run")
        assertContains(reason, "map recorded at 1a2b3c4d5e6f")
        assertContains(reason, "(limit 3)")
        assertNotNull(due(count = "9", builds = 3))
    }

    @Test
    fun `D days after a full run is due, just under is not`() {
        val days = 7
        assertNull(due(time = now.minus(Duration.ofDays(7)).plusSeconds(1).toString(), days = days))
        val reason = assertNotNull(due(time = now.minus(Duration.ofDays(8)).toString(), days = days))
        assertContains(reason, "8 days since the last full run")
        assertContains(reason, "(limit 7)")
    }

    @Test
    fun `either limit due is due, and the builds limit is named first`() {
        assertNotNull(due(count = "0", time = now.minus(Duration.ofDays(30)).toString(), builds = 20, days = 7))
        val both = assertNotNull(due(count = "19", time = now.minus(Duration.ofDays(30)).toString(), builds = 20, days = 7))
        assertContains(both, "20 builds since")
    }

    @Test
    fun `a count or time that cannot be read is due and says which`() {
        assertContains(assertNotNull(due(count = "x", builds = 20)), Schedule.COUNT_FILE)
        assertContains(assertNotNull(due(count = null, builds = 20)), Schedule.COUNT_FILE)
        assertContains(assertNotNull(due(count = "-1", builds = 20)), Schedule.COUNT_FILE)
        assertContains(assertNotNull(due(time = "yesterday", days = 7)), Schedule.TIME_FILE)
        assertContains(assertNotNull(due(time = null, days = 7)), Schedule.TIME_FILE)
    }

    @Test
    fun `a time within a day in the future is accepted, further is due`() {
        assertNull(due(time = now.plus(Duration.ofHours(23)).toString(), days = 7))
        assertContains(assertNotNull(due(time = now.plus(Duration.ofDays(2)).toString(), days = 7)), "in the future")
    }

    @Test
    fun `with no map there is nothing to bound`() {
        assertNull(Schedule.due(mapExists = false, null, null, null, now, builds = 1, days = 1))
    }

    @Test
    fun `how far away the next full run is`() {
        assertEquals(
            "full run due in 3 builds or by 2026-10-17",
            Schedule.untilDue("16", now.minus(Duration.ofDays(5)).toString(), builds = 20, days = 7),
        )
        assertEquals("full run due in 1 build", Schedule.untilDue("18", null, builds = 20, days = null))
        assertNull(Schedule.untilDue("0", null, builds = null, days = null))
    }

    @Test
    fun `a full run resets the count and records the time`(@TempDir dir: File) {
        File(dir, Schedule.COUNT_FILE).writeText("7\n")

        assertNull(Schedule.record(dir, fullRun = true, now))

        assertEquals("0", File(dir, Schedule.COUNT_FILE).readText().trim())
        assertEquals(now, Instant.parse(File(dir, Schedule.TIME_FILE).readText().trim()))
    }

    @Test
    fun `a run that was not a full run adds one, and an unreadable count stays unreadable`(@TempDir dir: File) {
        File(dir, Schedule.COUNT_FILE).writeText("7\n")
        Schedule.record(dir, fullRun = false, now)
        assertEquals("8", File(dir, Schedule.COUNT_FILE).readText().trim())

        File(dir, Schedule.COUNT_FILE).writeText("x\n")
        Schedule.record(dir, fullRun = false, now)
        assertEquals("x", File(dir, Schedule.COUNT_FILE).readText().trim(), "a guessed count would postpone the bound")
    }

    @Test
    fun `a map that cannot be written says so rather than throwing`(@TempDir dir: File) {
        val missing = File(dir, "no-such-dir")

        assertNotNull(Schedule.record(missing, fullRun = true, now))
    }
}
