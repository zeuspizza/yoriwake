package io.github.zeuspizza.yoriwake.gradle

import io.github.zeuspizza.yoriwake.gradle.change.RefusalKind
import io.github.zeuspizza.yoriwake.gradle.wiring.FilterVerdict
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Which decline a selecting run takes over `--tests`. The run and the explanation both read it, so
 * a filter whose patterns cannot be read runs everything in both.
 */
class NamedTestsDeclineTest {

    @Test
    fun `a filter whose patterns cannot be read declines as undetermined, whatever it names`() {
        val verdict = FilterVerdict(
            false, "the test filter cannot be read", filterReadable = false, commandLinePatterns = setOf("FooTest"),
        )

        assertEquals(
            RefusalKind.DECLINE_UNDETERMINED to
                "the test filter cannot be read, so whether tests were named with --tests is unknown",
            verdict.namedTestsDecline(),
        )
    }

    @Test
    fun `named tests decline, naming the patterns in order`() {
        val verdict = FilterVerdict(false, "--tests", commandLinePatterns = setOf("b.BTest", "a.ATest"))

        assertEquals(
            RefusalKind.TESTS_NAMED to "tests were named with --tests a.ATest b.BTest",
            verdict.namedTestsDecline(),
        )
    }

    @Test
    fun `a build-script filter alone declines nothing`() {
        assertNull(FilterVerdict(false, "filter.includePatterns=[*Test]").namedTestsDecline())
    }
}
