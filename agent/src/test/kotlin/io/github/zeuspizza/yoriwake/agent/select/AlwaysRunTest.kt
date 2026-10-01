package io.github.zeuspizza.yoriwake.agent.select

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The per-test escape hatch for tests known to be unsafe to skip, such as order-dependent ones.
 *
 * The failure guarded against is a pin that silently protects nothing: a typo'd pattern matches no
 * test and the build stays green, so the matcher must prove it matched.
 */
class AlwaysRunTest {

    private fun of(vararg patterns: String) = AlwaysRun.from(patterns.joinToString(","))

    @Test
    fun `a pattern matches the class it names`() {
        val pinned = of("com.acme.FlakyTest")

        assertTrue(pinned.matches("com.acme.FlakyTest"))
        assertTrue(pinned.matches("com.acme.FlakyTest.someMethod"))
    }

    @Test
    fun `a star matches a family`() {
        val pinned = of("com.acme.order.*")

        assertTrue(pinned.matches("com.acme.order.AaaTest"))
        assertTrue(pinned.matches("com.acme.order.deep.ZzzTest.method"))
        assertFalse(pinned.matches("com.acme.other.AaaTest"))
    }

    @Test
    fun `a single method can be pinned without its siblings`() {
        val pinned = of("com.acme.FlakyTest.needsTheOtherOne")

        assertTrue(pinned.matches("com.acme.FlakyTest.needsTheOtherOne"))
        assertFalse(pinned.matches("com.acme.FlakyTest.independent"))
    }

    @Test
    fun `an unrelated test is not pinned`() {
        assertFalse(of("com.acme.FlakyTest").matches("com.acme.SteadyTest"))
    }

    @Test
    fun `the matcher records whether it ever matched anything`() {
        // Nothing else in the output distinguishes a typo'd pattern from a suite needing no pins.
        val pinned = of("com.acme.Typoo*")

        assertEquals(setOf("com.acme.Typoo*"), pinned.unmatched())

        pinned.matches("com.acme.SteadyTest")
        assertEquals(setOf("com.acme.Typoo*"), pinned.unmatched(), "a miss must not count as a match")

        pinned.matches("com.acme.TypooTest")
        assertEquals(emptySet(), pinned.unmatched())
    }

    @Test
    fun `each pattern is tracked separately, so one working pin does not vouch for a broken one`() {
        val pinned = of("com.acme.Real*", "com.acme.Typoo*")

        pinned.matches("com.acme.RealTest")

        assertEquals(setOf("com.acme.Typoo*"), pinned.unmatched())
    }

    @Test
    fun `a pattern that would pin everything is refused, and says what to use instead`() {
        // `*` is selection switched off, yet would report narrowing on every run.
        val refused = assertFailsWith<IllegalArgumentException> { of("*") }

        assertTrue(refused.message!!.contains("*"), refused.message!!)
        assertTrue(refused.message!!.contains("yoriwake.disabled"), refused.message!!)
    }

    @Test
    fun `an empty configuration pins nothing and is not an error`() {
        // The common case must cost nothing and not look like a misconfiguration.
        assertFalse(AlwaysRun.from("").matches("com.acme.AnyTest"))
        assertFalse(AlwaysRun.from(null).matches("com.acme.AnyTest"))
        assertEquals(emptySet(), AlwaysRun.from("").unmatched())
    }

    @Test
    fun `whitespace and empty entries are ignored rather than becoming a match-everything pattern`() {
        // `-Pyoriwake.alwaysRun=a,,b` must not leave an empty pattern behind that matches every test.
        val pinned = AlwaysRun.from(" com.acme.A , , com.acme.B ")

        assertTrue(pinned.matches("com.acme.A"))
        assertTrue(pinned.matches("com.acme.B"))
        assertFalse(pinned.matches("com.acme.Unrelated"))
    }

    @Test
    fun `a regex metacharacter in a pattern is a literal, not a wildcard`() {
        // Treating dots as "any character" would quietly pin siblings the user never named.
        val pinned = of("com.acme.FlakyTest")

        assertFalse(pinned.matches("comXacmeXFlakyTest"))
        assertTrue(pinned.matches("com.acme.FlakyTest"))
    }

    @Test
    fun `the tag is namespaced so it cannot collide with a host's own vocabulary`() {
        assertTrue(AlwaysRun.TAG.startsWith("yoriwake-"), AlwaysRun.TAG)
    }
}
