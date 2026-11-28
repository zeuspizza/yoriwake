package io.github.zeuspizza.yoriwake.gradle.capture

import io.github.zeuspizza.yoriwake.agent.contract.AgentContract
import org.jacoco.core.data.ExecutionData
import org.jacoco.core.data.ExecutionDataWriter
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The map names the oldest JaCoCo whose agent recorded any of its records. */
class JacocoVersionRecordTest {

    /** One worker whose single test, `T<test>`, covered one class. */
    private fun worker(mapDir: File, test: String) {
        CoverageDecoder.recordsDir(mapDir).deleteRecursively()
        val dir = File(CoverageDecoder.recordsDir(mapDir), "worker-1").apply { mkdirs() }
        val out = ByteArrayOutputStream()
        ExecutionDataWriter(out).visitClassExecution(ExecutionData(1, "com/acme/C", booleanArrayOf(true)))
        File(dir, "000001.exec").writeBytes(out.toByteArray())
        File(dir, "index.tsv").writeText("1\t1000000\t${out.size()}\tSUCCESSFUL\t[class:T$test]/[method:t()]\n")
        File(dir, AgentContract.RAW_SCHEMA_VERSION_FILE).writeText("${AgentContract.RAW_SCHEMA_VERSION}\n")
    }

    private val commit = "a".repeat(40)

    private fun decode(mapDir: File, test: String, jacoco: String?, dates: Boolean) {
        worker(mapDir, test)
        CoverageDecoder.decode(mapDir, listOf("com.acme"), captureCommit = commit, datesTheMap = dates, jacocoVersion = jacoco)
    }

    @Test
    fun `a dating capture names its own JaCoCo and a merge keeps the older`(@TempDir mapDir: File) {
        decode(mapDir, "A", "0.8.15", dates = true)
        assertEquals("0.8.15", CoverageDecoder.readJacocoVersion(mapDir))

        decode(mapDir, "B", "0.8.14", dates = false)
        assertEquals("0.8.14", CoverageDecoder.readJacocoVersion(mapDir))

        decode(mapDir, "C", "0.8.15", dates = false)
        assertEquals("0.8.14", CoverageDecoder.readJacocoVersion(mapDir))

        decode(mapDir, "A", "0.8.15", dates = true)
        assertEquals("0.8.15", CoverageDecoder.readJacocoVersion(mapDir))
    }

    @Test
    fun `an unknown JaCoCo on either side of a merge leaves the map without one`(@TempDir mapDir: File) {
        decode(mapDir, "A", "0.8.15", dates = true)
        decode(mapDir, "B", null, dates = false)
        assertNull(CoverageDecoder.readJacocoVersion(mapDir))

        decode(mapDir, "C", "0.8.15", dates = false)
        assertNull(CoverageDecoder.readJacocoVersion(mapDir))
    }

    @Test
    fun `a map with no capture commit has no JaCoCo`(@TempDir mapDir: File) {
        decode(mapDir, "A", "0.8.15", dates = false)

        assertNull(CoverageDecoder.readJacocoVersion(mapDir))
    }

    @Test
    fun `a JaCoCo another capture stamped is not believed`(@TempDir mapDir: File) {
        decode(mapDir, "A", "0.8.15", dates = true)
        // A later capture by a yoriwake that does not write the file dates the map and leaves it.
        File(mapDir, CoverageDecoder.CAPTURE_COMMIT_FILE).writeText("b".repeat(40) + "\n")
        assertNull(CoverageDecoder.readJacocoVersion(mapDir))

        // Nor is it merged into a capture that keeps the records it does not speak for.
        decode(mapDir, "B", "0.8.15", dates = false)
        assertNull(CoverageDecoder.readJacocoVersion(mapDir))
    }

    @Test
    fun `a JaCoCo stamped before another capture added its JVMs is not believed`(@TempDir mapDir: File) {
        decode(mapDir, "A", "0.8.15", dates = true)
        // Such a capture that does not date the map leaves the capture commit and moves the positions.
        File(mapDir, AgentContract.POSITIONS_FILE).appendText("other/worker-9\t1\t[class:TB]/[method:t()]\n")

        assertNull(CoverageDecoder.readJacocoVersion(mapDir))
    }

    @Test
    fun `an unstamped JaCoCo is not believed`(@TempDir mapDir: File) {
        decode(mapDir, "A", "0.8.15", dates = true)
        File(mapDir, CoverageDecoder.JACOCO_VERSION_FILE).writeText("0.8.15\n")

        assertNull(CoverageDecoder.readJacocoVersion(mapDir))
    }
}
