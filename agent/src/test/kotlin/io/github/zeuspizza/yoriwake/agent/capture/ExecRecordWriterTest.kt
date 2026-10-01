package io.github.zeuspizza.yoriwake.agent.capture

import io.github.zeuspizza.yoriwake.agent.contract.AgentContract
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExecRecordWriterTest {

    @Test
    fun `writes one exec file per record and indexes it`(@TempDir dir: File) {
        ExecRecordWriter(dir, "1").use { writer ->
            writer.write("[class:A]/[method:a()]", 1_000_000, "SUCCESSFUL", byteArrayOf(1, 2, 3))
            writer.write("[class:B]/[method:b()]", 2_000_000, "SUCCESSFUL", byteArrayOf(4, 5))
        }

        val workerDir = File(dir, "worker-1")
        assertEquals(byteArrayOf(1, 2, 3).toList(), File(workerDir, "000001.exec").readBytes().toList())
        assertEquals(byteArrayOf(4, 5).toList(), File(workerDir, "000002.exec").readBytes().toList())

        val index = File(workerDir, "index.tsv").readLines()
        assertEquals(
            listOf(
                "1	1000000	3	SUCCESSFUL	[class:A]/[method:a()]",
                "2	2000000	2	SUCCESSFUL	[class:B]/[method:b()]",
            ),
            index,
        )
    }

    @Test
    fun `sequence numbers start at one and increase`(@TempDir dir: File) {
        ExecRecordWriter(dir, "1").use { writer ->
            assertEquals(1, writer.write("a", 0, "SUCCESSFUL", ByteArray(0)))
            assertEquals(2, writer.write("b", 0, "SUCCESSFUL", ByteArray(0)))
            assertEquals(3, writer.write("c", 0, "SUCCESSFUL", ByteArray(0)))
            assertEquals(3, writer.recordCount())
        }
    }

    @Test
    fun `separate workers never share a directory`(@TempDir dir: File) {
        // Gradle forks parallel test JVMs, each with its own agent and its own sequence counter.
        // Sharing a directory would interleave two independent sequences into one index.
        ExecRecordWriter(dir, "1").use { it.write("a", 0, "SUCCESSFUL", byteArrayOf(1)) }
        ExecRecordWriter(dir, "2").use { it.write("b", 0, "SUCCESSFUL", byteArrayOf(2)) }

        assertEquals(byteArrayOf(1).toList(), File(dir, "worker-1/000001.exec").readBytes().toList())
        assertEquals(byteArrayOf(2).toList(), File(dir, "worker-2/000001.exec").readBytes().toList())
    }

    @Test
    fun `records are buffered until flushed`(@TempDir dir: File) {
        // A deliberate trade: writing on every test makes record I/O dominate the overhead. A run
        // killed before a flush loses the unflushed batch.
        val writer = ExecRecordWriter(dir, "1")
        writer.write("a", 5, "SUCCESSFUL", byteArrayOf(9))

        assertTrue(
            File(dir, "worker-1/index.tsv").readLines().isEmpty(),
            "nothing should reach disk before a flush",
        )

        writer.close()
        assertEquals(listOf("1	5	1	SUCCESSFUL	a"), File(dir, "worker-1/index.tsv").readLines())
    }

    @Test
    fun `an explicit flush makes records readable without closing`(@TempDir dir: File) {
        val writer = ExecRecordWriter(dir, "1")
        try {
            writer.write("a", 5, "SUCCESSFUL", byteArrayOf(9))
            writer.flush()

            assertEquals(listOf("1	5	1	SUCCESSFUL	a"), File(dir, "worker-1/index.tsv").readLines())
            assertEquals(1, File(dir, "worker-1/000001.exec").length().toInt())
        } finally {
            writer.close()
        }
    }

    @Test
    fun `flushing twice does not duplicate records`(@TempDir dir: File) {
        ExecRecordWriter(dir, "1").use { writer ->
            writer.write("a", 5, "SUCCESSFUL", byteArrayOf(9))
            writer.flush()
            writer.flush()
        }

        assertEquals(1, File(dir, "worker-1/index.tsv").readLines().size)
    }

    @Test
    fun `the overhead summary is written after the records it describes`(@TempDir dir: File) {
        ExecRecordWriter(dir, "1").use { writer ->
            writer.write("a", 5, "SUCCESSFUL", byteArrayOf(9))
            writer.writeOverhead("captureOverhead agentMs=1")
        }

        // writeOverhead flushes first, so a reader that finds overhead.txt can trust the index
        // beside it is the complete run rather than a prefix of it.
        assertEquals(1, File(dir, "worker-1/index.tsv").readLines().size)
        assertContains(File(dir, "worker-1/overhead.txt").readText(), "captureOverhead")
    }

    @Test
    fun `tabs and newlines in a test id cannot corrupt the index, and the id survives`(@TempDir dir: File) {
        // Replacing them with spaces, as this once did, let two different ids share one record.
        val id = "weird\tid\nsecond\\line\r"
        ExecRecordWriter(dir, "1").use { it.write(id, 0, "SUCCESSFUL", ByteArray(0)) }

        val lines = File(dir, "worker-1/index.tsv").readLines()
        assertEquals(1, lines.size, "a single record must occupy a single line")
        val fields = io.github.zeuspizza.yoriwake.agent.contract.Tsv.split(lines[0])
        assertEquals(5, fields.size, "the id shifted a column: ${lines[0]}")
        assertEquals(id, fields[4])
    }

    @Test
    fun `an empty execution data blob is still recorded`(@TempDir dir: File) {
        // A test that touches no instrumented code must produce an empty record, not no record --
        // "covered nothing" and "was never run" mean different things to a selector.
        ExecRecordWriter(dir, "1").use { it.write("a", 0, "SUCCESSFUL", ByteArray(0)) }

        assertTrue(File(dir, "worker-1/000001.exec").exists())
        assertEquals(0, File(dir, "worker-1/000001.exec").length())
    }

    @Test
    fun `refuses to write into a directory that already holds a capture`(@TempDir dir: File) {
        // The sequence restarts at 1 per writer, so appending would pair old index rows with new
        // coverage -- a corrupted map that still parses cleanly.
        ExecRecordWriter(dir, "1").use { it.write("a", 0, "SUCCESSFUL", byteArrayOf(1)) }

        val error = runCatching { ExecRecordWriter(dir, "1") }.exceptionOrNull()

        assertTrue(error is java.io.IOException, "expected refusal, got $error")
        assertContains(error.message.orEmpty(), "already contains records")
    }

    @Test
    fun `a fresh directory is accepted`(@TempDir dir: File) {
        ExecRecordWriter(dir, "1").use { it.write("a", 0, "SUCCESSFUL", byteArrayOf(1)) }
        ExecRecordWriter(dir, "2").use { it.write("b", 0, "SUCCESSFUL", byteArrayOf(2)) }

        assertEquals(1, File(dir, "worker-1/index.tsv").readLines().size)
        assertEquals(1, File(dir, "worker-2/index.tsv").readLines().size)
    }

    @Test
    fun `reports the directory it writes to`(@TempDir dir: File) {
        ExecRecordWriter(dir, "7").use {
            assertEquals("worker-7", it.directory().name)
            assertFalse(it.directory().listFiles().isNullOrEmpty())
        }
    }
}

class ExecRecordWriterCheckpointTest {

    @Test
    fun `checkpoints periodically so a killed run keeps most of its records`(@TempDir dir: File) {
        // Checkpointing bounds what a killed JVM loses; the flush is inside the metered window, so
        // its cost is counted.
        val writer = ExecRecordWriter(dir, "1")
        repeat(1200) { writer.write("t$it", 0, "SUCCESSFUL", byteArrayOf(1)) }

        val onDisk = File(dir, "worker-1/index.tsv").readLines().size

        assertTrue(onDisk >= 1000, "expected at least two checkpoints of 500, got $onDisk")
        assertTrue(onDisk < 1200, "the tail should still be buffered until close")

        writer.close()
        assertEquals(1200, File(dir, "worker-1/index.tsv").readLines().size)
    }
}

class ExecRecordWriterPartialFailureTest {

    /** Fails the Nth exec-file write, simulating disk pressure partway through a flush. */
    private fun writerFailingAt(dir: File, failAtSequence: Int): ExecRecordWriter {
        // The exec file for a sequence is created by name, so making that path un-writable is the
        // least invasive way to fail exactly one record's write.
        File(dir, "worker-1").mkdirs()
        File(dir, "worker-1/%06d.exec".format(failAtSequence)).mkdirs()
        return ExecRecordWriter(dir, "1")
    }

    @Test
    fun `a failed flush does not duplicate the records it already wrote`(@TempDir dir: File) {
        // A failure partway through a flush must not rewrite already-written records on the next
        // attempt, which would duplicate index rows under existing sequence numbers.
        val writer = writerFailingAt(dir, failAtSequence = 2)
        writer.write("a", 0, "SUCCESSFUL", byteArrayOf(1))
        writer.write("b", 0, "SUCCESSFUL", byteArrayOf(2))
        writer.write("c", 0, "SUCCESSFUL", byteArrayOf(3))

        runCatching { writer.flush() }

        File(dir, "worker-1/000002.exec").delete()
        runCatching { writer.close() }

        val sequences = File(dir, "worker-1/index.tsv").readLines().map { it.split('\t')[0] }
        assertEquals(sequences.distinct(), sequences, "no sequence may appear twice: $sequences")
    }

    @Test
    fun `records written before a failure are not lost`(@TempDir dir: File) {
        val writer = writerFailingAt(dir, failAtSequence = 3)
        try {
            writer.write("a", 0, "SUCCESSFUL", byteArrayOf(1))
            writer.write("b", 0, "SUCCESSFUL", byteArrayOf(2))
            writer.write("c", 0, "SUCCESSFUL", byteArrayOf(3))

            runCatching { writer.flush() }

            // Without a flush in a finally, the index rows of records that did succeed would be
            // lost with the failed one, and the retry cannot recover them.
            val ids = File(dir, "worker-1/index.tsv").readLines().map { it.split('\t')[4] }
            assertContains(ids, "a")
            assertContains(ids, "b")
        } finally {
            // Windows cannot delete a file with an open handle, so @TempDir cleanup needs this.
            runCatching { writer.close() }
        }
    }
}

class ExecRecordWriterSchemaVersionTest {

    @Test
    fun `a capture records the schema version it was written with`(@TempDir dir: File) {
        // Without a version marker, an old map yields a parse error naming neither cause nor remedy.
        ExecRecordWriter(dir, "1").use { it.write("a", 0, "SUCCESSFUL", byteArrayOf(1)) }

        val version = File(dir, "worker-1/${AgentContract.RAW_SCHEMA_VERSION_FILE}").readText().trim()

        assertEquals(AgentContract.RAW_SCHEMA_VERSION.toString(), version)
    }

    @Test
    fun `the version is written before any record, so a killed run is still identifiable`(
        @TempDir dir: File,
    ) {
        val writer = ExecRecordWriter(dir, "1")
        try {
            assertTrue(File(dir, "worker-1/${AgentContract.RAW_SCHEMA_VERSION_FILE}").exists())
        } finally {
            writer.close()
        }
    }
}
