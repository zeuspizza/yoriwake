package dev.demokt

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AlphaTest {
    @Test
    fun doubles() {
        assertEquals(2, Alpha().twice(1))
    }

    @Test
    fun `one is positive`() {
        assertTrue(alphaPositive(1))
    }
}
