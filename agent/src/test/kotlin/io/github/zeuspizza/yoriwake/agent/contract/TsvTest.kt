package io.github.zeuspizza.yoriwake.agent.contract

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class TsvTest {

    @Test
    fun `a field with a tab, newline, carriage return and backslash round-trips`() {
        val field = "[class:a\\b]/[method:x(\t)]\r\nnext \\t literal"
        val row = Tsv.join("SUCCESSFUL", field, "")

        assertFalse('\n' in row || '\r' in row, "a separator survived escaping: $row")
        assertEquals(listOf("SUCCESSFUL", field, ""), Tsv.split(row).toList())
    }

    @Test
    fun `an ordinary field is written as it is`() {
        val id = "[engine:junit-jupiter]/[class:com.acme.ATest]/[method:a(java.lang.String)]"

        assertEquals("1\t0\t$id", Tsv.join("1", "0", id))
    }

    @Test
    fun `an escape it did not write, and a trailing backslash, stay as written`() {
        assertEquals("a\\qb\\", Tsv.unescape("a\\qb\\"))
    }
}
