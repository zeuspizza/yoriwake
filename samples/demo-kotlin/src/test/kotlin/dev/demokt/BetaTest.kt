package dev.demokt

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BetaTest {
    @Test
    fun `has a label`() {
        assertEquals("Beta", Beta().label())
    }

    @Test
    fun `two is even`() {
        assertTrue(betaEven(2))
    }
}
