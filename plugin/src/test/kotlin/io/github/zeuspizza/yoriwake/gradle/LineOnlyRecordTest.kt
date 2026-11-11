package io.github.zeuspizza.yoriwake.gradle

import io.github.zeuspizza.yoriwake.agent.contract.AgentContract
import io.github.zeuspizza.yoriwake.agent.contract.Tsv
import io.github.zeuspizza.yoriwake.gradle.capture.CoverageDecoder
import org.jacoco.core.data.ExecutionData
import org.jacoco.core.data.ExecutionDataWriter
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a capture records for `narrowLineOnlyChanges`: the stamped line digests, and when each JVM
 * first could have seen a class's lines or text.
 */
class LineOnlyRecordTest {

    @TempDir
    lateinit var dir: File

    private val commit = "a".repeat(40)

    private fun execData(name: String): ByteArray {
        val out = ByteArrayOutputStream()
        ExecutionDataWriter(out).visitClassExecution(ExecutionData(1, name.replace('.', '/'), booleanArrayOf(true)))
        return out.toByteArray()
    }

    /** One worker that ran tests a and b, each covering its own class, with [touches]. */
    private fun capture(worker: String = "1", vararg touches: String?) {
        val records = File(CoverageDecoder.recordsDir(dir), "worker-$worker").apply { mkdirs() }
        listOf("com.acme.A", "com.acme.B").forEachIndexed { i, name ->
            File(records, "%06d.exec".format(i + 1)).writeBytes(execData(name))
        }
        File(records, AgentContract.INDEX_FILE).writeText(
            "1\t1\t1\tSUCCESSFUL\t[class:com.acme.ATest]/[method:a()]\n2\t1\t1\tSUCCESSFUL\t[class:com.acme.BTest]/[method:b()]\n"
        )
        File(records, AgentContract.RAW_SCHEMA_VERSION_FILE).writeText("${AgentContract.RAW_SCHEMA_VERSION}\n")
        File(records, AgentContract.TOUCHES_FILE).writeText(touches.filterNotNull().joinToString("") { "$it\n" })
    }

    private fun decode(lines: Map<String, String>? = null) =
        CoverageDecoder.decode(dir, listOf("com.acme"), captureCommit = commit, datesTheMap = true, lineDigests = lines)

    /** The debug-touch rows by class, with their sequences, and the JVMs whose traces were recorded. */
    private fun debugRows(): List<Pair<String, Int>> =
        File(dir, AgentContract.DEBUG_TOUCH_FILE).readLines().drop(1).filter(String::isNotBlank)
            .map { Tsv.split(it).let { row -> row[2] to row[1].toInt() } }

    private val started = "1\t${AgentContract.TOUCH_PLAN_STARTED}\t"
    private val recorded = "0\t${AgentContract.TOUCH_STACKS_RECORDED}\t"
    private val complete = AgentContract.TOUCHES_COMPLETE

    @Test
    fun `a stack touch becomes a debug touch, and a load does not`() {
        capture(
            "1", recorded, started,
            "1\t${AgentContract.TOUCH_LOADED}\tcom.acme.Loaded",
            "2\t${AgentContract.TOUCH_STACK}\tcom.acme.Thrower",
            "2\t${AgentContract.TOUCH_STACK}\torg.elsewhere.OutOfScope",
            complete,
        )
        decode()

        val rows = debugRows().toMap()
        assertEquals(0, rows[AgentContract.DEBUG_RECORDED])
        assertEquals(2, rows["com.acme.Thrower"])
        assertNull(rows["com.acme.Loaded"])
        assertNull(rows["org.elsewhere.OutOfScope"])
    }

    @Test
    fun `a read of a class or source file becomes a debug touch`() {
        capture("1", recorded, started, "2\t${AgentContract.TOUCH_READ}\t/work/src/main/java/com/acme/Source.java", complete)
        decode()

        assertEquals(2, debugRows().toMap()["com.acme.Source"])
    }

    @Test
    fun `a line number seen during discovery is dated at the JVM's start`() {
        capture("1", recorded, "1\t${AgentContract.TOUCH_STACK}\tcom.acme.Early", started, complete)
        decode()

        assertEquals(0, debugRows().toMap()["com.acme.Early"])
    }

    @Test
    fun `a stack hook the JDK lacks dates every class, and leaves the first touches alone`() {
        capture("1", "0\t${AgentContract.TOUCH_STACK_ALL}\tthis JDK has no hook", started, complete)
        decode()

        assertEquals(0, debugRows().toMap()[AgentContract.FIRST_TOUCH_ANY])
        assertFalse(File(dir, AgentContract.FIRST_TOUCH_FILE).readText().contains(AgentContract.FIRST_TOUCH_ANY))
    }

    @Test
    fun `a child process dates every class from where it started`() {
        capture("1", recorded, started, "2\t${AgentContract.TOUCH_ALL}\ta child process was started", complete)
        decode()

        assertEquals(2, debugRows().toMap()[AgentContract.FIRST_TOUCH_ANY])
    }

    @Test
    fun `a worker whose observation did not end dates every class from its start`() {
        capture("1", recorded, started)
        decode()

        assertEquals(0, debugRows().toMap()[AgentContract.FIRST_TOUCH_ANY])
    }

    @Test
    fun `a JVM that did not record its stack traces has no recorded row`() {
        capture("1", started, complete)
        decode()

        assertTrue(debugRows().none { it.first == AgentContract.DEBUG_RECORDED }, debugRows().toString())
    }

    @Test
    fun `the debug touches speak for the map until another decode moves its JVMs`() {
        capture("1", recorded, started, complete)
        decode()
        assertTrue(CoverageDecoder.debugTouchesCurrent(dir))

        // A decode that did not write the file, as an older plugin's, leaves positions it does not name.
        val positions = File(dir, AgentContract.POSITIONS_FILE)
        positions.appendText("other/worker-9\t1\t[class:com.acme.CTest]/[method:c()]\n")
        assertFalse(CoverageDecoder.debugTouchesCurrent(dir))
    }

    @Test
    fun `line digests are read back only under the map's capture commit`() {
        capture("1", recorded, started, complete)
        decode(mapOf("com.acme.A" to "1111", "com.acme.B" to "2222"))

        assertEquals(mapOf("com.acme.A" to "1111", "com.acme.B" to "2222"), CoverageDecoder.readLineDigests(dir))

        File(dir, CoverageDecoder.CAPTURE_COMMIT_FILE).writeText("b".repeat(40) + "\n")
        assertNull(CoverageDecoder.readLineDigests(dir))
    }

    @Test
    fun `a torn line-digest table reads as absent`() {
        capture("1", recorded, started, complete)
        decode(mapOf("com.acme.A" to "1111"))
        val table = File(dir, CoverageDecoder.LINE_DIGESTS_FILE)
        table.writeText(table.readText().trimEnd('\n'))

        assertNull(CoverageDecoder.readLineDigests(dir))
    }

    @Test
    fun `a walk that did not finish removes the table, and a run that does not date the map leaves it`() {
        capture("1", recorded, started, complete)
        decode(mapOf("com.acme.A" to "1111"))
        CoverageDecoder.decode(dir, listOf("com.acme"), captureCommit = commit, datesTheMap = false, lineDigests = null)
        assertEquals(mapOf("com.acme.A" to "1111"), CoverageDecoder.readLineDigests(dir))

        decode(null)
        assertFalse(File(dir, CoverageDecoder.LINE_DIGESTS_FILE).exists())
    }
}
