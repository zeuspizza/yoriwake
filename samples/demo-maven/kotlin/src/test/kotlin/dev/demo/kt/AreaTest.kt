package dev.demo.kt

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AreaTest {
    @Test
    fun squares() {
        assertEquals(9, Area().ofSquare(3))
    }
}
