package io.github.zeuspizza.yoriwake.agent.platform

import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.platform.engine.discovery.DiscoverySelectors
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder
import org.junit.platform.launcher.core.LauncherFactory
import org.junit.platform.launcher.listeners.SummaryGeneratingListener
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Which tests ran to an outcome, as the agent's listener records them in this very test JVM: the
 * agent is attached here with capture off, as on a selecting run that narrowed, and the plan running
 * these tests is the outermost one. The order matters: the last test reads what the earlier ones left.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class RanTestsTest {

    private fun id(method: String) =
        "[engine:junit-jupiter]/[class:${RanTestsTest::class.java.name}]/[method:$method()]"

    @Test
    @Order(1)
    fun `a test that passes`() {
    }

    @Test
    @Order(2)
    fun `a test whose assumption aborts it`() {
        Assumptions.assumeTrue(false, "aborted on purpose")
    }

    @Test
    @Order(3)
    @Disabled("skipped on purpose")
    fun `a test the engine skips`() {
    }

    @Test
    @Order(4)
    fun `a test that finished is recorded with its outcome, an aborted or skipped one is not`() {
        assertEquals("SUCCESSFUL", RanTests.outcome(id("a test that passes")))
        assertNull(RanTests.outcome(id("a test whose assumption aborts it")))
        assertNull(RanTests.outcome(id("a test the engine skips")))
    }

    @Test
    fun `only a passed or failed outcome is kept`() {
        val prefix = "ran-tests-outcomes:"
        RanTests.record(prefix + "passed", "SUCCESSFUL")
        RanTests.record(prefix + "failed", "FAILED")
        RanTests.record(prefix + "aborted", "ABORTED")
        RanTests.record(prefix + "unknown", null)

        assertEquals("SUCCESSFUL", RanTests.outcome(prefix + "passed"))
        assertEquals("FAILED", RanTests.outcome(prefix + "failed"))
        assertNull(RanTests.outcome(prefix + "aborted"))
        assertNull(RanTests.outcome(prefix + "unknown"))
    }

    @Test
    fun `a launcher started inside a test records none of its tests`() {
        val summary = SummaryGeneratingListener()
        System.setProperty(NESTED, "true")
        try {
            LauncherFactory.create().execute(
                LauncherDiscoveryRequestBuilder.request()
                    .selectors(DiscoverySelectors.selectClass(NestedFixture::class.java))
                    .build(),
                summary,
            )
        } finally {
            System.clearProperty(NESTED)
        }

        assertEquals(1, summary.summary.testsSucceededCount, "the nested launcher ran nothing")
        assertNull(
            RanTests.outcome("[engine:junit-jupiter]/[class:${NestedFixture::class.java.name}]/[method:passes()]"),
            "a nested launcher's test was recorded as the task's",
        )
    }

    /** Run only by the nested launcher above; the outer plan skips it. */
    @EnabledIfSystemProperty(named = NESTED, matches = "true")
    class NestedFixture {
        @Test
        fun passes() {
        }
    }

    private companion object {
        const val NESTED = "yoriwake.test.nestedFixture"
    }
}
