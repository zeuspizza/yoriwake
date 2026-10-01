package dev.demokt

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Its own bytecode contains the code under test, because `aboveZero` is inline. Skipping it for a
 * change to Inlined.kt would be a silent miss.
 */
class InlinedTest {
    @Test
    fun `positive numbers are above zero`() {
        assertTrue(aboveZero(1))
    }

    @Test
    fun `negative numbers are not`() {
        assertFalse(aboveZero(-1))
    }

    @Test
    fun `small numbers are below ten`() {
        assertTrue(belowTen(9))
    }
}
