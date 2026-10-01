package dev.demokt

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Reads `LIMIT` directly. `const val` is folded into call sites, so this class file holds a literal
 * `10` and nothing links it to `ConstantsKt`: no probe fires and no SMAP entry exists. A changed class
 * with a `ConstantValue` attribute must therefore force a full run, or this test is skipped.
 */
class ConstantReaderTest {
    @Test
    fun `the limit is ten`() {
        assertEquals(10, LIMIT)
    }
}
