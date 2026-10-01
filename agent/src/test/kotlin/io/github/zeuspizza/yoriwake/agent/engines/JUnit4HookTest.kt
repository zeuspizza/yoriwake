package io.github.zeuspizza.yoriwake.agent.engines

import io.github.zeuspizza.yoriwake.agent.host.GradleHost
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** Stand-ins for Gradle's JUnit 4 processor, in the three shapes its `stop()` has taken. */
class StraightStop {
    var stops = 0
    fun stop() {
        stops++
    }
}

class BranchingStop {
    var started = true
    var stops = 0
    fun stop() {
        if (started) stops++
    }
}

class LoopingStop {
    var stops = 0
    fun stop() {
        while (stops < 3) stops++
    }
}

/**
 * The rewrite of Gradle's processor, which is how plain JUnit 4 learns its run finished.
 *
 * Its bytecode lands in Gradle's own worker, so a class the verifier rejects fails the host's
 * build outright. Each case loads the rewritten class and runs it, which is what verifies it.
 */
class JUnit4HookTest {

    private val property: String = GradleHost.RUN_FINISHED_PROPERTY

    @AfterEach
    fun clear() {
        System.clearProperty(property)
    }

    private fun rewrite(type: Class<*>): Any {
        val bytes = type.getResourceAsStream("/" + type.name.replace('.', '/') + ".class")!!.readBytes()
        val rewritten = Class.forName("io.github.zeuspizza.yoriwake.agent.engines.JUnit4Hook")
            .getDeclaredMethod("rewriteProcessor", ByteArray::class.java)
            .apply { isAccessible = true }
            .invoke(null, bytes) as ByteArray?
        assertNotNull(rewritten, "${type.simpleName} could not be rewritten")
        val loader = object : ClassLoader(type.classLoader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> =
                if (name == type.name) defineClass(name, rewritten, 0, rewritten.size) else super.loadClass(name, resolve)
        }
        return loader.loadClass(type.name).getDeclaredConstructor().newInstance()
    }

    private fun stop(instance: Any): Int {
        instance.javaClass.getMethod("stop").invoke(instance)
        return instance.javaClass.getMethod("getStops").invoke(instance) as Int
    }

    @Test
    fun `stop says the run finished and still does what it did`() {
        assertEquals(1, stop(rewrite(StraightStop::class.java)))
        assertEquals("true", System.getProperty(property))
    }

    @Test
    fun `a stop that branches keeps frames the verifier accepts`() {
        assertEquals(1, stop(rewrite(BranchingStop::class.java)))
        assertEquals("true", System.getProperty(property))
    }

    @Test
    fun `a stop whose first instruction is a branch target keeps frames the verifier accepts`() {
        assertEquals(3, stop(rewrite(LoopingStop::class.java)))
        assertEquals("true", System.getProperty(property))
    }
}
