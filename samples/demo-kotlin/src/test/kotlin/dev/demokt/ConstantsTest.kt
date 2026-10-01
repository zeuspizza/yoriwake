package dev.demokt

import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * Reads the constant through a function rather than referencing LIMIT directly, so the test's own
 * bytecode does not simply carry the inlined value with no edge to the holder at all.
 */
class ConstantsTest {
    @Test
    fun `nine is within the limit`() {
        assertTrue(withinLimit(9))
    }
}
