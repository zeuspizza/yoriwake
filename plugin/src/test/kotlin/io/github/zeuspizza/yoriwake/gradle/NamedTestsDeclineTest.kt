package io.github.zeuspizza.yoriwake.gradle

import io.github.zeuspizza.yoriwake.gradle.change.RefusalKind
import io.github.zeuspizza.yoriwake.gradle.wiring.FilterVerdict
import io.github.zeuspizza.yoriwake.gradle.wiring.TestPatterns
import io.github.zeuspizza.yoriwake.gradle.wiring.decideFilterVerdict
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Which decline a selecting run takes over `--tests`, or over patterns added to the filter after the
 * build script ran. The run and the explanation both read it, so a filter whose patterns cannot be
 * read runs everything in both.
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

    /** The verdict for a filter the build script left as [fromBuildScript] and that now reads [live]. */
    private fun verdict(
        live: TestPatterns,
        fromBuildScript: TestPatterns = TestPatterns(emptySet(), emptySet()),
        commandLine: Set<String> = emptySet(),
        unreadable: String? = null,
    ) = decideFilterVerdict(unreadable, commandLine, fromBuildScript, live, null, true, "(whole task)")

    @Test
    fun `patterns added for this run decline, naming them`() {
        val verdict = verdict(
            live = TestPatterns(setOf("*Test", "b.BTest", "a.ATest"), emptySet()),
            fromBuildScript = TestPatterns(setOf("*Test"), emptySet()),
        )

        assertEquals(
            RefusalKind.TESTS_NAMED to "tests were named for this run (filter.includePatterns=[a.ATest, b.BTest])",
            verdict.namedTestsDecline(),
        )
    }

    @Test
    fun `tests named with --tests beside added patterns decline for --tests`() {
        val verdict = verdict(live = TestPatterns(setOf("a.ATest"), emptySet()), commandLine = setOf("c.CTest"))

        assertEquals(
            RefusalKind.TESTS_NAMED to "tests were named with --tests c.CTest",
            verdict.namedTestsDecline(),
        )
    }

    @Test
    fun `a filter that cannot be read declines as undetermined beside added patterns`() {
        val verdict = verdict(live = TestPatterns(setOf("a.ATest"), emptySet()), unreadable = "a.Filter")

        assertEquals(RefusalKind.DECLINE_UNDETERMINED, verdict.namedTestsDecline()?.first)
    }

    @Test
    fun `exclude patterns added for this run decline nothing`() {
        assertNull(verdict(live = TestPatterns(emptySet(), setOf("a.ATest"))).namedTestsDecline())
    }
}
