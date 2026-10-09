package io.github.zeuspizza.yoriwake.gradle

import io.github.zeuspizza.yoriwake.agent.contract.AgentContract
import io.github.zeuspizza.yoriwake.gradle.wiring.AfterTest
import io.github.zeuspizza.yoriwake.gradle.wiring.RestoreHostCoverage
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AfterTestTest {

    private val logger = Logging.getLogger(AfterTestTest::class.java)

    @Test
    fun `steps run in order, and one that throws does not stop the next`(@TempDir dir: File) {
        val ran = mutableListOf<String>()
        val pending = File(dir, "pending").apply { writeText("pending") }
        val hook = AfterTest(
            pending,
            listOf(step { ran += "first"; error("broken") }, step { ran += "second" }),
        )

        hook.runIfPending(":test", logger)

        assertEquals(listOf("first", "second"), ran)
        assertFalse(pending.exists())
    }

    @Test
    fun `nothing runs once the task's own doLast has run`(@TempDir dir: File) {
        val ran = mutableListOf<String>()

        AfterTest(File(dir, "pending"), listOf(step { ran += "step" })).runIfPending(":test", logger)

        assertEquals(emptyList(), ran)
    }

    @Test
    fun `the run's records are appended to what JaCoCo wrote, in worker and sequence order`(@TempDir dir: File) {
        val records = File(dir, "raw")
        record(records, "worker-b", 1, "B1")
        record(records, "worker-a", 1, "A1")
        record(records, "worker-a", 2, "A2")
        val target = File(dir, "jacoco/test.exec").apply { parentFile.mkdirs(); writeText("JACOCO") }

        restore(records, target)

        assertEquals("JACOCOA1A2B1", target.readText())
    }

    @Test
    fun `a record a killed JVM left half written is not appended`(@TempDir dir: File) {
        val records = File(dir, "raw")
        record(records, "worker-a", 1, "A1")
        // Its row says three bytes, but only two reached the disk.
        record(records, "worker-a", 2, "A2", length = 3)
        // Written, but its row never was.
        File(records, "worker-a/" + String.format(AgentContract.EXEC_FILE_FORMAT, 3)).writeText("A3")
        val target = File(dir, "test.exec")

        restore(records, target)

        assertEquals("A1", target.readText())
    }

    @Test
    fun `a run that captured nothing leaves no file where JaCoCo wrote none`(@TempDir dir: File) {
        val target = File(dir, "test.exec")

        restore(File(dir, "raw"), target)

        assertFalse(target.exists())
    }

    @Test
    fun `a target that cannot be written is left as JaCoCo wrote it`(@TempDir dir: File) {
        val records = File(dir, "raw")
        record(records, "worker-a", 1, "A1")
        // A directory where the temporary copy would go: the copy fails.
        val target = File(dir, "test.exec").apply { writeText("JACOCO") }
        File(dir, "test.exec.yoriwake").mkdirs()
        File(dir, "test.exec.yoriwake/held").writeText("")

        restore(records, target)

        assertEquals("JACOCO", target.readText())
        assertTrue(File(dir, "test.exec.yoriwake").isDirectory)
    }

    private fun restore(records: File, target: File) {
        val destination = ProjectBuilder.builder().build().provider { target }
        RestoreHostCoverage(records, destination).run(":test", logger)
    }

    private fun record(records: File, worker: String, sequence: Int, bytes: String, length: Int = bytes.length) {
        val dir = File(records, worker).apply { mkdirs() }
        File(dir, String.format(AgentContract.EXEC_FILE_FORMAT, sequence)).writeText(bytes)
        File(dir, AgentContract.INDEX_FILE).appendText("$sequence\t1\t$length\tSUCCESSFUL\tdev.sample.T#t\n")
    }

    private fun step(body: () -> Unit) = object : AfterTest.Step {
        override fun run(taskPath: String, logger: Logger) = body()
    }
}
