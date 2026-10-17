package dev.demokt

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class TallyTest {
    @Test
    fun `adds up what it is given`() {
        val tally = Tally()
        tally.add(2)
        tally.add(3)
        assertEquals(5, tally.total())
    }

    @Test
    fun `prints its count`() {
        assertEquals("Tally(0)", Tally().toString())
    }

    @Test
    fun `hashes by identity`() {
        val tally = Tally()
        assertEquals(System.identityHashCode(tally), tally.hashCode())
    }
}
