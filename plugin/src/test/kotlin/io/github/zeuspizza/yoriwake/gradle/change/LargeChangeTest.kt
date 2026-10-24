package io.github.zeuspizza.yoriwake.gradle.change

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LargeChangeTest {

    @Test
    fun `only a count above the threshold refuses, naming both`() {
        assertNull(LargeChange.refusal(5_000, 5_000, "main"))
        assertEquals(
            "5001 paths changed against main, above the large-change threshold of 5000",
            LargeChange.refusal(5_001, 5_000, "main"),
        )
    }
}
