package io.github.zeuspizza.yoriwake.agent.capture

import io.github.zeuspizza.yoriwake.agent.contract.AgentContract
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** What the stack hooks' sink keeps: each class a stack trace element named, once. */
class StackTouchTest {

    private fun drained(recorder: TouchRecorder) = recorder.drain().map { it[0] to it[1] }

    private fun element(className: String) = StackTraceElement(className, "run", "Source.java", 7)

    @Test
    fun `each class a trace names is kept once`() {
        val recorder = TouchRecorder()
        recorder.stack(arrayOf(element("com.acme.Beta"), element("com.acme.Beta"), element("com.acme.Alpha"), null))
        recorder.stack(element("com.acme.Beta"))

        assertEquals(
            listOf(AgentContract.TOUCH_STACK to "com.acme.Beta", AgentContract.TOUCH_STACK to "com.acme.Alpha"),
            drained(recorder),
        )
    }

    @Test
    fun `a thread dump's traces and an element built for a module's class are kept by class`() {
        val recorder = TouchRecorder()
        recorder.stack(mapOf(Thread.currentThread() to arrayOf(element("com.acme.Worker"))))
        recorder.stack("app.module/com.acme.StepsKt")

        assertEquals(
            listOf(AgentContract.TOUCH_STACK to "com.acme.Worker", AgentContract.TOUCH_STACK to "com.acme.StepsKt"),
            drained(recorder),
        )
    }

    @Test
    fun `a line number handed out with no element counts as every class's`() {
        val recorder = TouchRecorder()
        recorder.stack(Any())

        assertEquals(AgentContract.TOUCH_STACK_ALL, drained(recorder).single().first)
    }

    @Test
    fun `nothing is kept from inside another sink, or for null`() {
        val recorder = TouchRecorder()
        recorder.unobserved { recorder.stack(element("com.acme.Agent")) }
        recorder.stack(null)

        assertEquals(emptyList(), drained(recorder))
    }
}
