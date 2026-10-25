package io.github.zeuspizza.yoriwake.agent.engines

import io.github.zeuspizza.yoriwake.agent.contract.AgentContract
import junit.framework.TestCase
import org.junit.jupiter.api.Test
import org.junit.runner.Request
import org.junit.runner.RunWith
import org.junit.runners.BlockJUnit4ClassRunner
import org.junit.runners.Parameterized
import org.junit.runners.Suite
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TwoTests {
    @org.junit.Test fun a() {}
    @org.junit.Test fun b() {}
}

@RunWith(Parameterized::class)
class Generated(@Suppress("unused") private val n: Int) {
    companion object {
        @JvmStatic @Parameterized.Parameters fun data() = listOf(arrayOf<Any>(1), arrayOf<Any>(2))
    }
    @org.junit.Test fun p() {}
}

class OwnRunner(type: Class<*>) : BlockJUnit4ClassRunner(type)

@RunWith(OwnRunner::class)
class RunByOwnRunner {
    @org.junit.Test fun a() {}
}

@RunWith(Suite::class)
@Suite.SuiteClasses(TwoTests::class)
class SuiteOfOthers

class Legacy : TestCase() {
    fun testOne() {}
}

class LegacySuite {
    companion object {
        @JvmStatic fun suite(): junit.framework.Test = junit.framework.TestSuite(Legacy::class.java)
    }
}

/**
 * Which runners a selecting run may leave out whole. A runner let through wrongly leaves out tests
 * it generates or borrows from another class, so only the exact runners that run a class's own
 * methods pass, and only while every test they describe is one of that class's methods.
 */
class JUnit4SelectionTest {

    private fun keepReason(type: Class<*>): String? {
        val runner = Request.aClass(type).runner
        val leaves = mutableListOf<Any>()
        JUnit4Selection.leavesOf(runner.description, leaves)
        return JUnit4Selection.keepReason(runner, runner.description.className, leaves)
    }

    @Test
    fun `JUnit 4's own runner over the class's own methods is judged`() {
        assertNull(keepReason(TwoTests::class.java))
    }

    @Test
    fun `JUnit 3's runner over a TestCase's own methods is judged`() {
        assertNull(keepReason(Legacy::class.java))
    }

    @Test
    fun `a runner that generates tests, or any runner not on the list, keeps its class`() {
        for (type in listOf(Generated::class.java, RunByOwnRunner::class.java, SuiteOfOthers::class.java)) {
            assertEquals(AgentContract.CLASS_RUNS_WHOLE_JUNIT4_RUNNER, keepReason(type), type.name)
        }
    }

    @Test
    fun `a class with a JUnit 3 suite method keeps its class`() {
        // JUnit runs it through a subclass of JUnit 3's runner, which is not that runner.
        assertEquals(AgentContract.CLASS_RUNS_WHOLE_JUNIT4_RUNNER, keepReason(LegacySuite::class.java))
    }

    @Test
    fun `JUnit 3's runner over another class's tests keeps its class`() {
        val runner = org.junit.internal.runners.JUnit38ClassRunner(junit.framework.TestSuite(Legacy::class.java, "Other"))
        val leaves = mutableListOf<Any>()
        JUnit4Selection.leavesOf(runner.description, leaves)

        assertEquals(AgentContract.CLASS_RUNS_WHOLE_JUNIT4_SHAPE,
            JUnit4Selection.keepReason(runner, "Other", leaves))
    }
}
