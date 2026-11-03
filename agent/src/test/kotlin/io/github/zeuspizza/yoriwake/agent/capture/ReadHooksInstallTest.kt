package io.github.zeuspizza.yoriwake.agent.capture

import org.junit.jupiter.api.Test
import java.lang.instrument.Instrumentation
import java.lang.reflect.Proxy
import java.util.function.Consumer
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Where the read hooks cannot be installed, every kind they report says so, which turns the capture
 * conservative: everything counts as touched, never nothing.
 */
class ReadHooksInstallTest {

    private val calls = mutableListOf<String>()

    /** An Instrumentation that does nothing but answer whether it can retransform. */
    private fun instrumentation(canRetransform: Boolean) = Proxy.newProxyInstance(
        javaClass.classLoader, arrayOf(Instrumentation::class.java),
    ) { _, method, _ ->
        calls += method.name
        when (method.returnType) {
            java.lang.Boolean.TYPE -> method.name == "isRetransformClassesSupported" && canRetransform
            else -> null
        }
    } as Instrumentation

    private val ignored = Consumer<Any> { }

    private fun install(instrumentation: Instrumentation) =
        ReadHooks.install(instrumentation, ignored, ignored, ignored, ignored, ignored, ignored, ignored, ignored)

    @Test
    fun `a JVM that cannot retransform reports every kind incomplete, saying why`() {
        val installed = install(instrumentation(canRetransform = false))

        val why = "this JVM cannot retransform classes"
        assertEquals(listOf(why, why, why), listOf(installed.reads, installed.lookups, installed.environment))
        assertFalse(installed.resolving)
        assertFalse("addTransformer" in calls)
    }

    @Test
    fun `a holder that cannot be defined reports every kind incomplete, naming the failure`() {
        // Its redefineModule opens nothing, so the holder cannot be defined into java.lang.
        val installed = install(instrumentation(canRetransform = true))

        for (reason in listOf(installed.reads, installed.lookups, installed.environment)) {
            assertTrue(reason!!.startsWith("the hooks could not be installed: "), reason)
        }
        assertEquals(installed.reads, installed.lookups)
        assertEquals(installed.reads, installed.environment)
        assertFalse(installed.resolving)
        assertTrue("redefineModule" in calls)
        assertFalse("addTransformer" in calls)
    }
}
