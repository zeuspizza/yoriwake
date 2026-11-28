package io.github.zeuspizza.yoriwake.agent.select

import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/**
 * A decision is made once per test JVM. One that fails must fail once too: retried for each test,
 * a map too large for the heap ran out of memory again on every test and the tests with it.
 */
class SelectionRunTest {

    @Test
    fun `a failed decision is not attempted again for the next test`() {
        var attempts = 0
        val heap = OutOfMemoryError("Java heap space")
        val run = SelectionRun({ }) {
            attempts++
            throw heap
        }

        assertSame(heap, assertFailsWith<OutOfMemoryError> { run.decide() })
        val later = assertFailsWith<IllegalStateException> { run.decide() }
        assertFailsWith<IllegalStateException> { run.decide() }

        assertEquals(1, attempts)
        assertSame(heap, later.cause)
        assertContains(later.message!!, "Java heap space")
    }
}
