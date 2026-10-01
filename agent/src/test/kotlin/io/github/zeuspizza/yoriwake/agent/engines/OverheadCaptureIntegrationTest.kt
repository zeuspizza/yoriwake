package io.github.zeuspizza.yoriwake.agent.engines

import io.github.zeuspizza.yoriwake.agent.capture.AgentLookup
import io.github.zeuspizza.yoriwake.agent.capture.JacocoAgent
import io.github.zeuspizza.yoriwake.agent.capture.ProbeReporter
import io.github.zeuspizza.yoriwake.agent.capture.ProbeResult
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.platform.engine.TestExecutionResult
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OverheadCaptureIntegrationTest {

    private class Sink : ProbeReporter {
        val results = mutableListOf<ProbeResult>()
        override fun report(result: ProbeResult) {
            results += result
        }
    }

    private class SlowAgent : JacocoAgent {
        override fun version() = "test"
        override fun takeExecutionData(): ByteArray {
            Thread.sleep(2)
            return ByteArray(128)
        }
    }

    @Test
    fun `writes an overhead summary beside the records`(@TempDir dir: File) {
        val listener = PlatformEvents({ AgentLookup.Result.found(SlowAgent()) }, Sink(), dir)
        listener.testPlanExecutionStarted(null)

        repeat(3) { i ->
            val id = TestIdentifiers.leaf("test$i")
            listener.executionStarted(id)
            listener.executionFinished(id, TestExecutionResult.successful())
        }
        listener.testPlanExecutionFinished(null)

        val overhead = File(dir.listFiles()!!.single(), "overhead.txt").readText()
        assertContains(overhead, "calls=")
        // Eight: one closing the pre-first-test window, two per test (closing the preceding
        // unattributed window and the test's own), and one closing the window after the last test.
        val calls = Regex("""calls=(\d+)""").find(overhead)!!.groupValues[1].toInt()
        assertEquals(8, calls)
    }
}
