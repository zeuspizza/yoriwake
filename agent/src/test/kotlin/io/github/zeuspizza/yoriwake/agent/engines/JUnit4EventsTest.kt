package io.github.zeuspizza.yoriwake.agent.engines

import org.junit.jupiter.api.Test
import org.junit.runner.Description
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Which tests a JUnit 4 class-level failure is recorded against. */
class JUnit4EventsTest {

    private fun classOf(name: String, vararg methods: String) = Description.createSuiteDescription(name).apply {
        methods.forEach { addChild(Description.createTestDescription(name, it)) }
    }

    @Test
    fun `a failed class names every test beneath it`() {
        assertEquals(
            listOf("[engine:junit4]/[class:dev.A]/[method:one]", "[engine:junit4]/[class:dev.A]/[method:two]"),
            JUnit4Events.testsBeneath(classOf("dev.A", "one", "two")),
        )
    }

    @Test
    fun `a failed suite names every test of every class beneath it`() {
        val suite = Description.createSuiteDescription("dev.AllTests").apply {
            addChild(classOf("dev.A", "one"))
            addChild(classOf("dev.B", "two"))
        }

        assertEquals(
            listOf("[engine:junit4]/[class:dev.A]/[method:one]", "[engine:junit4]/[class:dev.B]/[method:two]"),
            JUnit4Events.testsBeneath(suite),
        )
    }

    /** A description that cannot list what is beneath it. */
    class Unlistable {
        fun getClassName() = "dev.A"
        fun getMethodName(): String? = null
        fun getChildren(): List<Any> = throw IllegalStateException("broken")
    }

    @Test
    fun `a description that cannot be listed throws, so the capture counts it`() {
        assertFailsWith<IllegalStateException> { JUnit4Events.testsBeneath(Unlistable()) }
    }
}
