package io.github.zeuspizza.yoriwake.agent.select

import io.github.zeuspizza.yoriwake.agent.contract.AgentContract
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The one reader that decides whether a selection record may leave tests out. Any doubt about a
 * record makes it unusable whole, so a complement run runs everything rather than skip a test no
 * run showed finished.
 */
class SelectionRecordFileTest {

    private val alpha = "[engine:junit-jupiter]/[class:Alpha]/[method:t()]"
    private val beta = "[engine:junit-jupiter]/[class:Beta]/[method:t()]"

    private fun record(
        vararg rows: String,
        version: String? = AgentContract.SELECTION_VERSION,
        count: String? = rows.size.toString(),
    ): String = (listOfNotNull(
        version?.let { "#!${AgentContract.VERSION_NOTE}\t$it" },
        "#!${AgentContract.STAMP_COMMIT_NOTE}\tabc",
        count?.let { "#!${AgentContract.ROWS_NOTE}\t$it" },
    ) + rows).joinToString("\n", postfix = "\n")

    private fun assertUnusable(text: String?, why: String) {
        val read = SelectionRecordFile.read(text)
        assertNotNull(read.failure(), "read as usable: ${text?.replace("\n", "|")}")
        assertContains(read.failure()!!, why)
        assertTrue(read.ran().isEmpty())
        assertTrue(read.notes().isEmpty())
    }

    @Test
    fun `a well-formed record lists the tests that passed or failed, and its notes`() {
        val read = SelectionRecordFile.read(record("$alpha\tSUCCESSFUL", "$beta\tFAILED"))

        assertNull(read.failure())
        assertEquals(mapOf(alpha to "SUCCESSFUL", beta to "FAILED"), read.ran())
        assertEquals("abc", read.notes()[AgentContract.STAMP_COMMIT_NOTE])
    }

    @Test
    fun `no record is unusable`() = assertUnusable(null, "there is no record")

    @Test
    fun `a record of another version, or of none, is unusable`() {
        assertUnusable(record("$alpha\tSUCCESSFUL", version = "0"), "its version is 0")
        assertUnusable(record("$alpha\tSUCCESSFUL", version = null), "its version is null")
    }

    @Test
    fun `a row of a test that did not pass or fail makes the whole record unusable`() {
        for (outcome in listOf("ABORTED", "SKIPPED", "successful", "")) {
            assertUnusable(record("$alpha\tSUCCESSFUL", "$beta\t$outcome"), "a row is not a test that passed or failed")
        }
    }

    @Test
    fun `a row of another shape makes the whole record unusable`() {
        assertUnusable(record("$alpha\tSUCCESSFUL\textra"), "a row is not a test that passed or failed")
        assertUnusable(record("\tSUCCESSFUL"), "a row is not a test that passed or failed")
        assertUnusable(record(alpha), "a row is not a test that passed or failed")
    }

    @Test
    fun `a record without its row count is unusable`() {
        assertUnusable(record("$alpha\tSUCCESSFUL", count = null), "it holds 1 rows of null")
    }
}
