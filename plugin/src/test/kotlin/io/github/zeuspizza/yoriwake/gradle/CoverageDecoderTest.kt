package io.github.zeuspizza.yoriwake.gradle

import io.github.zeuspizza.yoriwake.agent.contract.AgentContract
import io.github.zeuspizza.yoriwake.agent.contract.Tsv
import io.github.zeuspizza.yoriwake.gradle.bytecode.EffectiveScope
import io.github.zeuspizza.yoriwake.gradle.bytecode.Recordability
import io.github.zeuspizza.yoriwake.gradle.capture.CoverageDecoder
import org.jacoco.core.data.ExecutionData
import org.jacoco.core.data.ExecutionDataWriter
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoverageDecoderTest {

    /** Builds a real JaCoCo blob, so decoding is exercised against the actual format. */
    private fun execData(vararg classes: Pair<String, BooleanArray>): ByteArray {
        val out = ByteArrayOutputStream()
        val writer = ExecutionDataWriter(out)
        classes.forEachIndexed { i, (name, probes) ->
            writer.visitClassExecution(ExecutionData(i.toLong() + 1, name.replace('.', '/'), probes))
        }
        return out.toByteArray()
    }

    private fun records(mapDir: File, worker: String, vararg rows: Triple<String, String, ByteArray>) {
        val dir = File(CoverageDecoder.recordsDir(mapDir), "worker-$worker").apply { mkdirs() }
        val index = StringBuilder()
        rows.forEachIndexed { i, (testId, outcome, blob) ->
            val seq = i + 1
            File(dir, "%06d.exec".format(seq)).writeBytes(blob)
            index.append("$seq\t1000000\t${blob.size}\t$outcome\t$testId\n")
        }
        File(dir, "index.tsv").writeText(index.toString())
        // The agent stamps each worker directory before its first record; the decoder refuses one
        // that is missing or foreign, so the fixture has to stamp too or it is not the agent.
        File(dir, AgentContract.RAW_SCHEMA_VERSION_FILE).writeText("${AgentContract.RAW_SCHEMA_VERSION}\n")
    }

    @Test
    fun `decodes covered classes into the plain text the agent reads`(@TempDir dir: File) {
        records(
            dir, "1",
            Triple("[class:A]/[method:a()]", "SUCCESSFUL", execData("com.acme.A" to booleanArrayOf(true))),
        )

        assertEquals(1, CoverageDecoder.decode(dir, listOf("com.acme"))?.captured)

        val line = File(dir, AgentContract.COVERAGE_FILE).readText().trim()
        assertEquals("SUCCESSFUL\t1000000\tcom.acme.A\t[class:A]/[method:a()]", line)
    }

    @Test
    fun `a test id with a tab, newline, backslash and carriage return reaches the selector intact`(
        @TempDir dir: File,
    ) {
        // Replaced with spaces, as the agent once did, two different ids could share one record.
        val id = "[class:A]/[method:a(\t)]\n\\\r"
        records(
            dir, "1",
            Triple(Tsv.escape(id), "SUCCESSFUL", execData("com.acme.A" to booleanArrayOf(true))),
        )

        CoverageDecoder.decode(dir, listOf("com.acme"))

        assertEquals(1, File(dir, AgentContract.COVERAGE_FILE).readLines().size)
        val entry = io.github.zeuspizza.yoriwake.agent.select.MapReader.read(dir).entries().single()
        assertEquals(id, entry.testId())
        assertEquals(setOf("com.acme.A"), entry.coveredClasses())
    }

    @Test
    fun `a test whose window covers nothing is UNKNOWN, not a successful record of nothing`(
        @TempDir dir: File,
    ) {
        // Empty is not "covers nothing": left SUCCESSFUL with no classes, no change would ever
        // select it. Robolectric's sandboxed classloaders produce this shape for whole suites.
        records(
            dir, "1",
            Triple("[engine:junit4]/[class:A]/[method:a()]", "SUCCESSFUL", execData()),
            Triple("[class:B]/[method:b()]", "SUCCESSFUL",
                   execData("com.acme.B" to booleanArrayOf(true))),
        )

        CoverageDecoder.decode(dir, listOf("com.acme"))

        val rows = File(dir, AgentContract.COVERAGE_FILE).readLines()
            .filter(String::isNotBlank).associateBy({ it.split('	')[3] }, { it.split('	')[0] })
        assertEquals("UNKNOWN", rows["[engine:junit4]/[class:A]/[method:a()]"])
        assertEquals("SUCCESSFUL", rows["[class:B]/[method:b()]"])
    }

    @Test
    fun `an empty between-tests window stays as it is, because nothing running is a real answer`(
        @TempDir dir: File,
    ) {
        // An empty class-scoped window legitimately means nothing ran between two tests; UNKNOWN
        // would force a full run on every build.
        records(
            dir, "1",
            Triple(
                "[yoriwake:class][engine:junit4]/[class:com.acme.ATest]|[engine:junit4]/[class:com.acme.BTest]",
                "NONE", execData(),
            ),
            Triple("[yoriwake:unattributed]", "NONE", execData()),
        )

        CoverageDecoder.decode(dir, listOf("com.acme"))

        File(dir, AgentContract.COVERAGE_FILE).readLines().filter(String::isNotBlank).forEach {
            assertEquals("NONE", it.split('	')[0], "rewrote a non-test window: $it")
        }
    }

    @Test
    fun `a class loaded but never executed is not recorded as covered`(@TempDir dir: File) {
        // Counting those would inflate every test's footprint toward the whole classpath.
        records(
            dir, "1",
            Triple(
                "t", "SUCCESSFUL",
                execData("com.acme.A" to booleanArrayOf(true), "com.acme.B" to booleanArrayOf(false)),
            ),
        )

        CoverageDecoder.decode(dir, listOf("com.acme"))

        val classes = File(dir, AgentContract.COVERAGE_FILE).readText().split('\t')[2]
        assertEquals("com.acme.A", classes)
    }

    @Test
    fun `classes outside the scope are filtered out`(@TempDir dir: File) {
        records(
            dir, "1",
            Triple(
                "t", "SUCCESSFUL",
                execData("com.acme.A" to booleanArrayOf(true), "org.other.B" to booleanArrayOf(true)),
            ),
        )

        CoverageDecoder.decode(dir, listOf("com.acme"))

        assertEquals("com.acme.A", File(dir, AgentContract.COVERAGE_FILE).readText().split('\t')[2])
    }

    @Test
    fun `every worker's records are decoded, not just the first`(@TempDir dir: File) {
        records(dir, "1", Triple("a", "SUCCESSFUL", execData("com.acme.A" to booleanArrayOf(true))))
        records(dir, "2", Triple("b", "SUCCESSFUL", execData("com.acme.B" to booleanArrayOf(true))))

        assertEquals(2, CoverageDecoder.decode(dir, listOf("com.acme"))?.captured)
    }

    @Test
    fun `a worker that wrote no completion marker is named as unfinished`(@TempDir dir: File) {
        // A test JVM that died mid-run still leaves records that decode cleanly; only the marker it
        // never wrote tells the finalizer the map is not current.
        records(dir, "1", Triple("a", "SUCCESSFUL", execData("com.acme.A" to booleanArrayOf(true))))
        records(dir, "2", Triple("b", "SUCCESSFUL", execData("com.acme.B" to booleanArrayOf(true))))
        File(CoverageDecoder.recordsDir(dir), "worker-1/${AgentContract.PLAN_COMPLETE_FILE}").writeText("")

        assertEquals(listOf("worker-2"), CoverageDecoder.unfinishedWorkers(dir))

        File(CoverageDecoder.recordsDir(dir), "worker-2/${AgentContract.PLAN_COMPLETE_FILE}").writeText("")
        assertEquals(emptyList(), CoverageDecoder.unfinishedWorkers(dir))
    }

    @Test
    fun `a worker that died before flushing a record is still unfinished`(@TempDir dir: File) {
        // It contributes nothing, so the older records of every test it was given survive.
        records(dir, "1", Triple("a", "SUCCESSFUL", execData("com.acme.A" to booleanArrayOf(true))))
        File(CoverageDecoder.recordsDir(dir), "worker-1/${AgentContract.PLAN_COMPLETE_FILE}").writeText("")
        records(dir, "2")

        assertEquals(listOf("worker-2"), CoverageDecoder.unfinishedWorkers(dir))
    }

    @Test
    fun `a run that captured nothing returns null and writes no map`(@TempDir dir: File) {
        // An empty map would read as "nothing covers anything", and a selector would run nothing.
        assertNull(CoverageDecoder.decode(dir, listOf("com.acme")))
        assertTrue(!File(dir, AgentContract.COVERAGE_FILE).exists())
    }

    @Test
    fun `a run that captured nothing leaves an existing map untouched`(@TempDir dir: File) {
        records(dir, "1", Triple("a", "SUCCESSFUL", execData("com.acme.A" to booleanArrayOf(true))))
        CoverageDecoder.decode(dir, listOf("com.acme"))
        val before = File(dir, AgentContract.COVERAGE_FILE).readText()

        File(CoverageDecoder.recordsDir(dir), "worker-1").deleteRecursively()

        assertNull(CoverageDecoder.decode(dir, listOf("com.acme")))
        assertEquals(before, File(dir, AgentContract.COVERAGE_FILE).readText())
    }

    @Test
    fun `a test covering nothing still gets an entry, and that entry says UNKNOWN`(
        @TempDir dir: File,
    ) {
        // The record must not be dropped (the merge supersedes by id), and it must be UNKNOWN: a
        // SUCCESSFUL test with no classes is one no change can ever select.
        records(dir, "1", Triple("t", "SUCCESSFUL", ByteArray(0)))

        assertEquals(1, CoverageDecoder.decode(dir, listOf("com.acme"))?.captured)
        assertContains(File(dir, AgentContract.COVERAGE_FILE).readText(), "UNKNOWN	1000000		t")
    }

    @Test
    fun `the outcome is carried through so the failed-test rule can use it`(@TempDir dir: File) {
        records(dir, "1", Triple("t", "FAILED", execData("com.acme.A" to booleanArrayOf(true))))

        CoverageDecoder.decode(dir, listOf("com.acme"))

        assertTrue(File(dir, AgentContract.COVERAGE_FILE).readText().startsWith("FAILED\t"))
    }

    @Test
    fun `the schema version is written beside the map`(@TempDir dir: File) {
        records(dir, "1", Triple("t", "SUCCESSFUL", execData("com.acme.A" to booleanArrayOf(true))))

        CoverageDecoder.decode(dir, listOf("com.acme"))

        assertEquals(
            AgentContract.MAP_SCHEMA_VERSION.toString(),
            File(dir, AgentContract.MAP_SCHEMA_VERSION_FILE).readText().trim(),
        )
    }

    @Test
    fun `a malformed index line is skipped rather than failing the decode`(@TempDir dir: File) {
        records(dir, "1", Triple("good", "SUCCESSFUL", execData("com.acme.A" to booleanArrayOf(true))))
        File(CoverageDecoder.recordsDir(dir), "worker-1/index.tsv").appendText("garbage\n")

        assertEquals(1, CoverageDecoder.decode(dir, listOf("com.acme"))?.captured)
    }

    @Test
    fun `an unreadable class window produces no record, so the previous one survives`(@TempDir dir: File) {
        // A window no test owns has no outcome to make UNKNOWN; an empty one would only lose the
        // coverage the older one carries.
        val window = "[yoriwake:class][engine:junit-jupiter]/[class:com.acme.ATest]"
        records(dir, "1", Triple(window, "NONE", execData("com.acme.A" to booleanArrayOf(true))))
        CoverageDecoder.decode(dir, listOf("com.acme"))
        CoverageDecoder.recordsDir(dir).deleteRecursively()

        records(dir, "1", Triple(window, "NONE", "not jacoco data".toByteArray()))

        assertNull(CoverageDecoder.decode(dir, listOf("com.acme")))
        assertContains(File(dir, AgentContract.COVERAGE_FILE).readText(), "com.acme.A")
    }

    @Test
    fun `a test that genuinely covered nothing still gets a record`(@TempDir dir: File) {
        // Distinct from the unreadable case above: an empty blob is a real answer.
        records(dir, "1", Triple("t", "SUCCESSFUL", ByteArray(0)))

        assertEquals(1, CoverageDecoder.decode(dir, listOf("com.acme"))?.captured)
    }

    @Test
    fun `a map missing its version marker is not merged into`(@TempDir dir: File) {
        // The version is written last, so an interrupted decode leaves the map unversioned and the
        // reader rejects it rather than half-believing it.
        records(dir, "1", Triple("a", "SUCCESSFUL", execData("com.acme.A" to booleanArrayOf(true))))
        CoverageDecoder.decode(dir, listOf("com.acme"))
        File(dir, AgentContract.MAP_SCHEMA_VERSION_FILE).delete()
        CoverageDecoder.recordsDir(dir).deleteRecursively()

        records(dir, "1", Triple("b", "SUCCESSFUL", execData("com.acme.B" to booleanArrayOf(true))))

        assertEquals(1, CoverageDecoder.decode(dir, listOf("com.acme"))?.total)
    }

    @Test
    fun `a later run merges into the map instead of replacing it`(@TempDir dir: File) {
        // A selected run only executes what it selected; replacing the map with that would make
        // the next run see every other test as unknown and run them all.
        records(
            dir, "1",
            Triple("alpha", "SUCCESSFUL", execData("com.acme.A" to booleanArrayOf(true))),
            Triple("beta", "SUCCESSFUL", execData("com.acme.B" to booleanArrayOf(true))),
        )
        CoverageDecoder.decode(dir, listOf("com.acme"))
        CoverageDecoder.recordsDir(dir).deleteRecursively()

        records(dir, "1", Triple("alpha", "FAILED", execData("com.acme.C" to booleanArrayOf(true))))
        val outcome = CoverageDecoder.decode(dir, listOf("com.acme"))

        assertEquals(1, outcome?.captured)
        assertEquals(2, outcome?.total)

        val lines = File(dir, AgentContract.COVERAGE_FILE).readLines().filter(String::isNotBlank)
        assertEquals(2, lines.size)
        assertTrue(lines.any { it.endsWith("	beta") && it.contains("com.acme.B") })
        assertTrue(lines.any { it.endsWith("	alpha") && it.contains("com.acme.C") && it.startsWith("FAILED") })
    }

    @Test
    fun `records sharing an id are all kept, because each carries different coverage`() {
        // Class-scoped setup coverage produces one record per window, all under the same id;
        // keeping one per id would lose coverage and skip tests silently.
        val dir = java.nio.file.Files.createTempDirectory("yoriwake-dupes").toFile()
        try {
            records(
                dir, "1",
                Triple("[yoriwake:class]scope", "NONE", execData("com.acme.A" to booleanArrayOf(true))),
                Triple("[yoriwake:class]scope", "NONE", execData("com.acme.B" to booleanArrayOf(true))),
            )

            assertEquals(2, CoverageDecoder.decode(dir, listOf("com.acme"))?.total)

            val text = File(dir, AgentContract.COVERAGE_FILE).readText()
            assertTrue(text.contains("com.acme.A"), "coverage of A was dropped")
            assertTrue(text.contains("com.acme.B"), "coverage of B was dropped")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a selecting run does not shrink the startup coverage a full run observed`(@TempDir dir: File) {
        // Every window of startup coverage shares one id, so superseding by id would let a run that
        // executed a fraction of the suite replace what a full run saw. Coverage that no test owns
        // is what forces a full run when it is touched; losing it loses the forcing rule, silently.
        val unattributed = "[yoriwake:unattributed]"
        records(
            dir, "1",
            Triple(unattributed, "NONE", execData("com.acme.Startup" to booleanArrayOf(true))),
            Triple(unattributed, "NONE", execData("com.acme.Boot" to booleanArrayOf(true))),
        )
        CoverageDecoder.decode(dir, listOf("com.acme"))
        CoverageDecoder.recordsDir(dir).deleteRecursively()

        records(dir, "1", Triple(unattributed, "NONE", execData("com.acme.Boot" to booleanArrayOf(true))))
        CoverageDecoder.decode(dir, listOf("com.acme"), selecting = true)

        val text = File(dir, AgentContract.COVERAGE_FILE).readText()
        assertContains(text, "com.acme.Startup")
        assertContains(text, "com.acme.Boot")
    }

    @Test
    fun `a full capture does replace startup coverage, because it saw everything`(@TempDir dir: File) {
        val unattributed = "[yoriwake:unattributed]"
        records(dir, "1", Triple(unattributed, "NONE", execData("com.acme.Gone" to booleanArrayOf(true))))
        CoverageDecoder.decode(dir, listOf("com.acme"))
        CoverageDecoder.recordsDir(dir).deleteRecursively()

        records(dir, "1", Triple(unattributed, "NONE", execData("com.acme.Now" to booleanArrayOf(true))))
        CoverageDecoder.decode(dir, listOf("com.acme"), selecting = false)

        val text = File(dir, AgentContract.COVERAGE_FILE).readText()
        assertContains(text, "com.acme.Now")
        assertTrue(!text.contains("com.acme.Gone"), "a full capture is the whole truth: $text")
    }

    private fun loaded(dir: File, worker: String, vararg classes: String) {
        val target = CoverageDecoder.loadedDir(dir).apply { mkdirs() }
        File(target, "loaded-$worker.txt").writeText(classes.joinToString("\n", postfix = "\n"))
        // The listener writes this only once the whole plan has run.
        File(target, "loaded-$worker.complete").createNewFile()
    }

    @Test
    fun `the loaded set is the union across workers, not the last one written`(@TempDir dir: File) {
        // Gradle forks several test JVMs. A single shared output file is last-writer-wins, and the
        // surviving file describes one worker while looking like the whole task.
        records(dir, "1", Triple("t", "SUCCESSFUL", execData("com.acme.A" to booleanArrayOf(true))))
        records(dir, "2", Triple("u", "SUCCESSFUL", execData("com.acme.B" to booleanArrayOf(true))))
        loaded(dir, "1", "com.acme.A", "com.acme.OnlyOnOne")
        loaded(dir, "2", "com.acme.A", "com.acme.OnlyOnTwo")

        CoverageDecoder.decode(dir, listOf("com.acme"), wholeTask = true, loadedScope = listOf("com.acme"))

        val union = File(dir, AgentContract.LOADED_FILE).readLines().filter(String::isNotBlank)
        assertEquals(listOf("com.acme.A", "com.acme.OnlyOnOne", "com.acme.OnlyOnTwo"), union)
        assertEquals("full", File(dir, AgentContract.LOADED_PROVENANCE_FILE).readText().trim())
        assertEquals("com.acme", File(dir, AgentContract.LOADED_SCOPE_FILE).readText().trim())
    }

    @Test
    fun `a worker that produced records but no loaded file voids the union`(@TempDir dir: File) {
        // A JVM killed before its shutdown hook ran leaves the union missing whatever only it
        // loaded. A shorter union means more skipping, so a union we cannot account for is refused
        // rather than trusted.
        records(dir, "1", Triple("t", "SUCCESSFUL", execData("com.acme.A" to booleanArrayOf(true))))
        records(dir, "2", Triple("u", "SUCCESSFUL", execData("com.acme.B" to booleanArrayOf(true))))
        loaded(dir, "1", "com.acme.A")

        CoverageDecoder.decode(dir, listOf("com.acme"), wholeTask = true, loadedScope = listOf("com.acme"))

        assertTrue(!File(dir, AgentContract.LOADED_FILE).exists())
    }

    @Test
    fun `a selecting run does not narrow the loaded set`(@TempDir dir: File) {
        // A selecting run executes a fraction of the suite and so loads a fraction of the classes.
        records(dir, "1", Triple("t", "SUCCESSFUL", execData("com.acme.A" to booleanArrayOf(true))))
        loaded(dir, "1", "com.acme.A", "com.acme.B", "com.acme.C")
        CoverageDecoder.decode(dir, listOf("com.acme"), wholeTask = true, loadedScope = listOf("com.acme"))
        CoverageDecoder.recordsDir(dir).deleteRecursively()
        CoverageDecoder.loadedDir(dir).deleteRecursively()

        records(dir, "1", Triple("t", "SUCCESSFUL", execData("com.acme.A" to booleanArrayOf(true))))
        CoverageDecoder.decode(dir, listOf("com.acme"), selecting = true)

        val union = File(dir, AgentContract.LOADED_FILE).readLines().filter(String::isNotBlank)
        assertEquals(listOf("com.acme.A", "com.acme.B", "com.acme.C"), union)
    }

    @Test
    fun `a capture without the recorder discards a union it cannot vouch for`(@TempDir dir: File) {
        // A full capture moves the map forward on evidence the union does not cover, so a stale
        // loaded directory must not be re-stamped as a fresh, complete union.
        records(dir, "1", Triple("t", "SUCCESSFUL", execData("com.acme.A" to booleanArrayOf(true))))
        loaded(dir, "1", "com.acme.A", "com.acme.B")
        CoverageDecoder.decode(dir, listOf("com.acme"), wholeTask = true, loadedScope = listOf("com.acme"))
        CoverageDecoder.recordsDir(dir).deleteRecursively()

        // A later capture with the recorder off: the stale loaded directory is still on disk.
        records(dir, "1", Triple("t", "SUCCESSFUL", execData("com.acme.A" to booleanArrayOf(true))))
        CoverageDecoder.decode(dir, listOf("com.acme"), wholeTask = false, loadedScope = listOf("com.acme"))

        assertTrue(!File(dir, AgentContract.LOADED_FILE).exists(), "a union nobody can vouch for")
        assertTrue(!File(dir, AgentContract.LOADED_PROVENANCE_FILE).exists())
    }

    @Test
    fun `a map from another schema version is discarded rather than merged`(@TempDir dir: File) {
        // Mixing two record formats in one file produces a map that parses and means something else.
        records(dir, "1", Triple("alpha", "SUCCESSFUL", execData("com.acme.A" to booleanArrayOf(true))))
        CoverageDecoder.decode(dir, listOf("com.acme"))
        // The previous version, literally: a 0.1.0 map may hold records merged without being dated.
        File(dir, AgentContract.MAP_SCHEMA_VERSION_FILE).writeText("6\n")
        CoverageDecoder.recordsDir(dir).deleteRecursively()

        records(dir, "1", Triple("beta", "SUCCESSFUL", execData("com.acme.B" to booleanArrayOf(true))))

        assertEquals(1, CoverageDecoder.decode(dir, listOf("com.acme"))?.total)
    }

    @Test
    fun `map health is measured over the whole map, not over what this run captured`(
        @TempDir dir: File,
    ) {
        // CI caching restores a map, runs selecting and captures a little; judged by this run
        // alone the map looks perfect.
        val empty = execData()
        records(
            dir, "1",
            Triple("poison-1", "SUCCESSFUL", empty),
            Triple("poison-2", "SUCCESSFUL", empty),
            Triple("poison-3", "SUCCESSFUL", empty),
            Triple("healthy", "SUCCESSFUL", execData("com.acme.A" to booleanArrayOf(true))),
        )
        CoverageDecoder.decode(dir, listOf("com.acme"))
        CoverageDecoder.recordsDir(dir).deleteRecursively()

        records(
            dir, "1",
            Triple("healthy", "SUCCESSFUL", execData("com.acme.A" to booleanArrayOf(true))),
        )
        val outcome = CoverageDecoder.decode(dir, listOf("com.acme"), selecting = true)!!

        assertEquals(0, outcome.unattributable, "this run captured nothing unattributable")
        assertEquals(3, outcome.mapUnattributable, "the map it selected from is mostly unusable")
        assertEquals(4, outcome.mapTests)
    }

    @Test
    fun `the decode reports how many workers it read records from`(@TempDir dir: File) {
        // `yoriwakeAudit` divides summed durations by this to estimate wall clock.
        records(dir, "1", Triple("t1", "SUCCESSFUL", execData("com.acme.A" to booleanArrayOf(true))))
        records(dir, "2", Triple("t2", "SUCCESSFUL", execData("com.acme.B" to booleanArrayOf(true))))
        records(dir, "3", Triple("t3", "SUCCESSFUL", execData("com.acme.C" to booleanArrayOf(true))))

        assertEquals(3, CoverageDecoder.decode(dir, listOf("com.acme"))!!.workers)
    }

    @Test
    fun `map health counts tests, not class windows or startup coverage`(@TempDir dir: File) {
        // Empty class-scoped windows and startup coverage are not tests; counting them would
        // make a healthy map look mostly unusable.
        val empty = execData()
        records(
            dir, "1",
            Triple("[yoriwake:class]/dev.acme.A", "SUCCESSFUL", empty),
            Triple("[yoriwake:class]/dev.acme.B", "SUCCESSFUL", empty),
            Triple("[yoriwake:unattributed]", "SUCCESSFUL", empty),
            Triple("healthy", "SUCCESSFUL", execData("com.acme.A" to booleanArrayOf(true))),
        )

        val outcome = CoverageDecoder.decode(dir, listOf("com.acme"))!!

        assertEquals(1, outcome.mapTests, "only the real test counts")
        assertEquals(0, outcome.mapUnattributable)
    }

    @Test
    fun `records left by another version of the agent are refused, not merged`(@TempDir dir: File) {
        // Records left by an earlier run may use another format, whose columns this decoder would
        // misread into a map that still parses.
        records(dir, "1", Triple("alpha", "SUCCESSFUL", execData("com.acme.A" to booleanArrayOf(true))))
        val worker = File(CoverageDecoder.recordsDir(dir), "worker-1")
        File(worker, AgentContract.RAW_SCHEMA_VERSION_FILE).writeText("1\n")

        val failure = assertFailsWith<IOException> { CoverageDecoder.decode(dir, listOf("com.acme")) }

        assertContains(failure.message.orEmpty(), "schema version 1")
        assertTrue(
            !File(dir, AgentContract.COVERAGE_FILE).exists(),
            "the previous map is left as it was rather than half-updated",
        )
    }

    @Test
    fun `a worker stamped under the map's file name is refused, not read as current`(@TempDir dir: File) {
        // A file under the old name may carry the current number, so reading either name would
        // merge records whose format nothing vouched for.
        records(dir, "1", Triple("alpha", "SUCCESSFUL", execData("com.acme.A" to booleanArrayOf(true))))
        val worker = File(CoverageDecoder.recordsDir(dir), "worker-1")
        File(worker, AgentContract.RAW_SCHEMA_VERSION_FILE).delete()
        File(worker, AgentContract.MAP_SCHEMA_VERSION_FILE).writeText("${AgentContract.RAW_SCHEMA_VERSION}\n")

        val failure = assertFailsWith<IOException> { CoverageDecoder.decode(dir, listOf("com.acme")) }

        assertContains(failure.message.orEmpty(), "schema version unknown")
    }

    @Test
    fun `one foreign worker refuses the whole capture rather than merging the rest`(
        @TempDir dir: File,
    ) {
        // Dropping the odd worker would write a map that silently misses every test it held.
        records(dir, "1", Triple("alpha", "SUCCESSFUL", execData("com.acme.A" to booleanArrayOf(true))))
        records(dir, "2", Triple("beta", "SUCCESSFUL", execData("com.acme.B" to booleanArrayOf(true))))
        File(File(CoverageDecoder.recordsDir(dir), "worker-2"), AgentContract.RAW_SCHEMA_VERSION_FILE).delete()

        assertFailsWith<IOException> { CoverageDecoder.decode(dir, listOf("com.acme")) }
    }

    @Test
    fun `a worker that recorded nothing is not mistaken for a foreign one`(@TempDir dir: File) {
        // An agent killed before stamping leaves an empty directory; refusing it would stop the
        // map from ever updating again.
        records(dir, "1", Triple("alpha", "SUCCESSFUL", execData("com.acme.A" to booleanArrayOf(true))))
        val empty = File(CoverageDecoder.recordsDir(dir), "worker-2").apply { mkdirs() }
        File(empty, "index.tsv").writeText("")

        assertEquals(1, CoverageDecoder.decode(dir, listOf("com.acme"))?.captured)
    }

    @Test
    fun `records the scope the run was actually instrumented under`(@TempDir dir: File) {
        records(dir, "1", Triple("[class:A]", "SUCCESSFUL", execData("com.acme.A" to booleanArrayOf(true))))

        CoverageDecoder.decode(
            dir,
            listOf("com.acme"),
            effectiveScope = EffectiveScope(listOf("com.acme.*"), listOf("com.acme.gen.*")).serialize(),
        )

        assertEquals(
            EffectiveScope(listOf("com.acme.*"), listOf("com.acme.gen.*")).serialize(),
            File(dir, AgentContract.EFFECTIVE_SCOPE_FILE).readText(),
        )
    }

    @Test
    fun `a run that cannot read its own scope removes the previous run's record of one`(@TempDir dir: File) {
        // Records merged in now have no nameable scope, so the old file must not vouch for them.
        records(dir, "1", Triple("[class:A]", "SUCCESSFUL", execData("com.acme.A" to booleanArrayOf(true))))
        CoverageDecoder.decode(dir, listOf("com.acme"), effectiveScope = EffectiveScope(listOf("com.acme.*"), emptyList()).serialize())

        records(dir, "1", Triple("[class:B]", "SUCCESSFUL", execData("com.acme.B" to booleanArrayOf(true))))
        CoverageDecoder.decode(dir, listOf("com.acme"), effectiveScope = null)

        assertFalse(File(dir, AgentContract.EFFECTIVE_SCOPE_FILE).exists())
    }

    @Test
    fun `a map merged across two different scopes records both of them`(@TempDir dir: File) {
        // Both scopes are kept, and the reader requires a class to be inside every one; deleting
        // them on a mismatch would lose the provenance permanently.
        val first = EffectiveScope(listOf("com.acme.*"), emptyList()).serialize()
        val second = EffectiveScope(listOf("com.acme.*"), listOf("com.acme.gen.*")).serialize()
        records(dir, "1", Triple("[class:A]", "SUCCESSFUL", execData("com.acme.A" to booleanArrayOf(true))))
        CoverageDecoder.decode(dir, listOf("com.acme"), effectiveScope = first, datesTheMap = true)

        records(dir, "1", Triple("[class:B]", "SUCCESSFUL", execData("com.acme.B" to booleanArrayOf(true))))
        CoverageDecoder.decode(dir, listOf("com.acme"), selecting = true, effectiveScope = second)

        val recorded = File(dir, AgentContract.EFFECTIVE_SCOPE_FILE).readText()

        assertEquals(2, EffectiveScope.parseAll(recorded).size, recorded)
        assertContains(recorded, "com.acme.gen.*")
    }

    @Test
    fun `a full capture replaces the accumulated scopes with its own`(@TempDir dir: File) {
        // Otherwise the list only grows, and one long-dead narrow scope refuses every class forever.
        // This pins the scope descriptor, not a claim that the retained records are gone.
        val narrow = EffectiveScope(listOf("com.acme.one.*"), emptyList()).serialize()
        val wide = EffectiveScope(listOf("com.acme.*"), emptyList()).serialize()
        records(dir, "1", Triple("[class:A]", "SUCCESSFUL", execData("com.acme.A" to booleanArrayOf(true))))
        CoverageDecoder.decode(dir, listOf("com.acme"), effectiveScope = narrow, datesTheMap = true)
        records(dir, "1", Triple("[class:B]", "SUCCESSFUL", execData("com.acme.B" to booleanArrayOf(true))))
        CoverageDecoder.decode(dir, listOf("com.acme"), selecting = true, effectiveScope = wide)

        records(dir, "1", Triple("[class:C]", "SUCCESSFUL", execData("com.acme.C" to booleanArrayOf(true))))
        CoverageDecoder.decode(dir, listOf("com.acme"), effectiveScope = wide, datesTheMap = true)

        assertEquals(wide, File(dir, AgentContract.EFFECTIVE_SCOPE_FILE).readText())
    }

    @Test
    fun `a run under an unchanged scope keeps the record, older entries and all`(@TempDir dir: File) {
        val scope = EffectiveScope(listOf("com.acme.*"), emptyList()).serialize()
        records(dir, "1", Triple("[class:A]", "SUCCESSFUL", execData("com.acme.A" to booleanArrayOf(true))))
        CoverageDecoder.decode(dir, listOf("com.acme"), effectiveScope = scope)

        records(dir, "1", Triple("[class:B]", "SUCCESSFUL", execData("com.acme.B" to booleanArrayOf(true))))
        CoverageDecoder.decode(dir, listOf("com.acme"), effectiveScope = scope)

        assertEquals(scope, File(dir, AgentContract.EFFECTIVE_SCOPE_FILE).readText())
    }

    @Test
    fun `a map that already had records can still acquire a scope, from a full capture`(@TempDir dir: File) {
        // A map with records but no recorded scope must be able to acquire one, or the rule would
        // refuse permanently and invisibly.
        val scope = EffectiveScope(listOf("com.acme.*"), emptyList()).serialize()
        records(dir, "1", Triple("[class:Old]", "SUCCESSFUL", execData("com.acme.Old" to booleanArrayOf(true))))
        CoverageDecoder.decode(dir, listOf("com.acme"), effectiveScope = null)
        assertFalse(File(dir, AgentContract.EFFECTIVE_SCOPE_FILE).exists())

        records(dir, "1", Triple("[class:New]", "SUCCESSFUL", execData("com.acme.New" to booleanArrayOf(true))))
        CoverageDecoder.decode(dir, listOf("com.acme"), effectiveScope = scope, datesTheMap = true)

        assertEquals(scope, File(dir, AgentContract.EFFECTIVE_SCOPE_FILE).readText())
    }

    @Test
    fun `a partial run may not stamp a scope over records it did not re-observe`(@TempDir dir: File) {
        // The other half of the same rule. A filtered or selecting run saw a fraction of the suite,
        // so the records it left standing were captured under whatever was in force then.
        val scope = EffectiveScope(listOf("com.acme.*"), emptyList()).serialize()
        records(dir, "1", Triple("[class:Old]", "SUCCESSFUL", execData("com.acme.Old" to booleanArrayOf(true))))
        CoverageDecoder.decode(dir, listOf("com.acme"), effectiveScope = null)

        records(dir, "1", Triple("[class:New]", "SUCCESSFUL", execData("com.acme.New" to booleanArrayOf(true))))
        CoverageDecoder.decode(dir, listOf("com.acme"), selecting = true, effectiveScope = scope, datesTheMap = false)

        assertFalse(File(dir, AgentContract.EFFECTIVE_SCOPE_FILE).exists())
    }

    // A recorded constant digest equal to the current one licenses skipping, so any path that
    // could produce a false match is safety-critical.

    private fun captureWith(dir: File, constants: Map<String, String>?, datesTheMap: Boolean) {
        records(
            dir, "1",
            Triple("[class:A]/[method:a()]", "SUCCESSFUL", execData("com.acme.A" to booleanArrayOf(true))),
        )
        CoverageDecoder.decode(
            dir, listOf("com.acme"), datesTheMap = datesTheMap, constants = constants,
        )
    }

    @Test
    fun `a full capture records the constants it read`(@TempDir dir: File) {
        captureWith(dir, mapOf("com.acme.A" to "abc123"), datesTheMap = true)

        assertEquals(mapOf("com.acme.A" to "abc123"), CoverageDecoder.readConstants(dir))
    }

    @Test
    fun `a capture that could not read the classes removes a stale record of them`(@TempDir dir: File) {
        // A digest left by an earlier capture could match and narrow past every test that baked in
        // the old value.
        captureWith(dir, mapOf("com.acme.A" to "abc123"), datesTheMap = true)
        assertTrue(File(dir, CoverageDecoder.CONSTANTS_FILE).isFile)

        captureWith(dir, null, datesTheMap = true)

        assertFalse(
            File(dir, CoverageDecoder.CONSTANTS_FILE).exists(),
            "a stale digest is worse than none: it licenses a skip the map cannot support",
        )
        assertEquals(emptyMap(), CoverageDecoder.readConstants(dir))
    }

    @Test
    fun `a run that did not observe the whole task leaves the recorded constants alone`(@TempDir dir: File) {
        // Same gate as `capture-commit`: a partial run must not vouch for records it never saw.
        // Older digests are the safe direction, since they mismatch and force.
        captureWith(dir, mapOf("com.acme.A" to "from-the-full-capture"), datesTheMap = true)

        captureWith(dir, mapOf("com.acme.A" to "from-a-partial-run"), datesTheMap = false)

        assertEquals(
            mapOf("com.acme.A" to "from-the-full-capture"),
            CoverageDecoder.readConstants(dir),
            "a partial run advanced the baseline a positive match now licenses skipping on",
        )
    }

    @Test
    fun `a partial run that could not read the classes does not delete the recorded ones either`(
        @TempDir dir: File,
    ) {
        // Deleting would be safe but would throw away the last full capture's baseline.
        captureWith(dir, mapOf("com.acme.A" to "from-the-full-capture"), datesTheMap = true)

        captureWith(dir, null, datesTheMap = false)

        assertEquals(mapOf("com.acme.A" to "from-the-full-capture"), CoverageDecoder.readConstants(dir))
    }

    @Test
    fun `a digest survives the round trip whatever the constant it summarises contained`(
        @TempDir dir: File,
    ) {
        // A TAB, newline, `=` or `;` in a constant must not break this TAB-delimited file; the
        // digest is a hash for that reason.
        val writer = org.objectweb.asm.ClassWriter(0)
        writer.visit(
            org.objectweb.asm.Opcodes.V1_8, org.objectweb.asm.Opcodes.ACC_PUBLIC,
            "com/acme/Nasty", null, "java/lang/Object", null,
        )
        writer.visitField(
            org.objectweb.asm.Opcodes.ACC_PUBLIC or org.objectweb.asm.Opcodes.ACC_STATIC or
                org.objectweb.asm.Opcodes.ACC_FINAL,
            "TEXT", "Ljava/lang/String;", null, "a\tb\nc=d;e",
        ).visitEnd()
        writer.visitEnd()
        val digest = Recordability.constantDigest(writer.toByteArray())!!

        captureWith(dir, mapOf("com.acme.Nasty" to digest), datesTheMap = true)

        assertEquals(mapOf("com.acme.Nasty" to digest), CoverageDecoder.readConstants(dir))
        assertEquals(
            1,
            File(dir, CoverageDecoder.CONSTANTS_FILE).readLines().count(String::isNotBlank),
            "one class must be one record, however many line breaks its constants held",
        )
    }

    @Test
    fun `a torn or truncated constants line is dropped rather than half-read`(@TempDir dir: File) {
        // Every line is a licence to skip, so an unparseable line must contribute nothing.
        File(dir, CoverageDecoder.CONSTANTS_FILE).also { it.parentFile.mkdirs() }.writeText(
            "com.acme.Good\tabc123\n" +
                "com.acme.NoDigest\n" +
                "\tdigest-with-no-class\n" +
                "\n"
        )

        assertEquals(mapOf("com.acme.Good" to "abc123"), CoverageDecoder.readConstants(dir))
    }

    @Test
    fun `a map with no constants file reads as nothing recorded, which forces`(@TempDir dir: File) {
        assertEquals(emptyMap(), CoverageDecoder.readConstants(dir))
    }

    // Whole-class digests follow the same write discipline as constants, so a match can safely
    // retire a forcing later.

    private fun captureDigests(dir: File, digests: Map<String, String>?, datesTheMap: Boolean) {
        records(
            dir, "1",
            Triple("[class:A]/[method:a()]", "SUCCESSFUL", execData("com.acme.A" to booleanArrayOf(true))),
        )
        CoverageDecoder.decode(
            dir, listOf("com.acme"), datesTheMap = datesTheMap, classDigests = digests,
        )
    }

    @Test
    fun `a full capture records the class digests it walked`(@TempDir dir: File) {
        captureDigests(dir, mapOf("com.acme.A" to "abc123", "com.acme.B" to "def456"), datesTheMap = true)

        assertEquals(
            mapOf("com.acme.A" to "abc123", "com.acme.B" to "def456"),
            CoverageDecoder.readClassDigests(dir),
        )
    }

    @Test
    fun `a walk that did not finish deletes a stale table rather than leaving it`(@TempDir dir: File) {
        captureDigests(dir, mapOf("com.acme.A" to "abc123"), datesTheMap = true)
        assertTrue(File(dir, CoverageDecoder.CLASS_DIGESTS_FILE).isFile)

        captureDigests(dir, null, datesTheMap = true)

        assertFalse(
            File(dir, CoverageDecoder.CLASS_DIGESTS_FILE).exists(),
            "a table describing a previous state of the output must not outlive the walk that failed",
        )
        assertNull(CoverageDecoder.readClassDigests(dir))
    }

    @Test
    fun `a narrowing run leaves the recorded class digests exactly as they were`(@TempDir dir: File) {
        // Same gate as `capture-commit` and the constants.
        captureDigests(dir, mapOf("com.acme.A" to "from-the-full-capture"), datesTheMap = true)

        captureDigests(dir, mapOf("com.acme.A" to "from-a-partial-run"), datesTheMap = false)

        assertEquals(
            mapOf("com.acme.A" to "from-the-full-capture"),
            CoverageDecoder.readClassDigests(dir),
        )
        captureDigests(dir, null, datesTheMap = false)
        assertEquals(
            mapOf("com.acme.A" to "from-the-full-capture"),
            CoverageDecoder.readClassDigests(dir),
            "a partial run that could not walk must not delete the last full capture's table either",
        )
    }

    @Test
    fun `a missing table is unknown, and an empty one is a build that compiled nothing`(
        @TempDir dir: File,
    ) {
        // Null is "no table to compare against"; empty is a finished walk that found no classes.
        assertNull(CoverageDecoder.readClassDigests(dir))

        captureDigests(dir, emptyMap(), datesTheMap = true)

        assertEquals(emptyMap(), CoverageDecoder.readClassDigests(dir))
    }

    @Test
    fun `a truncated table is refused whole rather than half-read`(@TempDir dir: File) {
        // Stricter than the constants reader: here a dropped line reads as "unchanged". An
        // interrupted write leaves a final line with no newline.
        File(dir, CoverageDecoder.CLASS_DIGESTS_FILE).also { it.parentFile.mkdirs() }
            .writeText("com.acme.Good\tabc123\ncom.acme.Cut\tdef4")

        assertNull(CoverageDecoder.readClassDigests(dir))

        File(dir, CoverageDecoder.CLASS_DIGESTS_FILE).writeText("com.acme.NoSeparator\n")
        assertNull(CoverageDecoder.readClassDigests(dir))

        File(dir, CoverageDecoder.CLASS_DIGESTS_FILE).writeText("\tdigest-with-no-class\n")
        assertNull(CoverageDecoder.readClassDigests(dir))
    }

    // The annotation table rides the same gate and the same strict reader as the class digests.

    private fun captureAnnotations(dir: File, digests: Map<String, String>?, datesTheMap: Boolean) {
        records(
            dir, "1",
            Triple("[class:A]/[method:a()]", "SUCCESSFUL", execData("com.acme.A" to booleanArrayOf(true))),
        )
        CoverageDecoder.decode(
            dir, listOf("com.acme"), datesTheMap = datesTheMap, annotationDigests = digests,
        )
    }

    @Test
    fun `a full capture records annotation digests, and only a full capture replaces them`(@TempDir dir: File) {
        captureAnnotations(dir, mapOf("com.acme.A" to "abc123"), datesTheMap = true)
        assertEquals(mapOf("com.acme.A" to "abc123"), CoverageDecoder.readAnnotationDigests(dir))

        captureAnnotations(dir, mapOf("com.acme.A" to "from-a-partial-run"), datesTheMap = false)
        assertEquals(mapOf("com.acme.A" to "abc123"), CoverageDecoder.readAnnotationDigests(dir))

        // A walk that did not finish leaves no table, which forces, rather than a stale one.
        captureAnnotations(dir, null, datesTheMap = true)
        assertNull(CoverageDecoder.readAnnotationDigests(dir))
    }

    @Test
    fun `a torn annotation table is refused whole`(@TempDir dir: File) {
        File(dir, CoverageDecoder.ANNOTATION_DIGESTS_FILE).also { it.parentFile.mkdirs() }
            .writeText("com.acme.Good\tabc123\ncom.acme.Cut\tdef4")

        assertNull(CoverageDecoder.readAnnotationDigests(dir))
    }

    @Test
    fun `either table decodes without the other`(@TempDir dir: File) {
        // Two independent files. A map written before this one existed still decodes, and a map
        // whose constants walk failed still carries its class digests.
        records(
            dir, "1",
            Triple("[class:A]/[method:a()]", "SUCCESSFUL", execData("com.acme.A" to booleanArrayOf(true))),
        )
        CoverageDecoder.decode(
            dir, listOf("com.acme"), datesTheMap = true,
            constants = mapOf("com.acme.A" to "constant-digest"), classDigests = null,
        )
        assertEquals(mapOf("com.acme.A" to "constant-digest"), CoverageDecoder.readConstants(dir))
        assertNull(CoverageDecoder.readClassDigests(dir))

        CoverageDecoder.decode(
            dir, listOf("com.acme"), datesTheMap = true,
            constants = null, classDigests = mapOf("com.acme.A" to "class-digest"),
        )
        assertEquals(emptyMap(), CoverageDecoder.readConstants(dir))
        assertEquals(mapOf("com.acme.A" to "class-digest"), CoverageDecoder.readClassDigests(dir))
    }

    @Test
    fun `the decode counts how many records it could not attribute`(@TempDir dir: File) {
        // Counted so the caller can warn; "N records captured" alone looks healthy.
        records(
            dir, "1",
            Triple("[engine:junit4]/[class:A]/[method:a()]", "SUCCESSFUL", execData()),
            Triple("[engine:junit4]/[class:B]/[method:b()]", "SUCCESSFUL", execData()),
            Triple("[class:C]/[method:c()]", "SUCCESSFUL",
                   execData("com.acme.C" to booleanArrayOf(true))),
            // Not a test: a class-scoped window is legitimately empty and must not be counted.
            Triple(
                "[yoriwake:class][engine:junit4]/[class:com.acme.ATest]|[engine:junit4]/[class:com.acme.BTest]",
                "NONE", execData(),
            ),
        )

        val outcome = CoverageDecoder.decode(dir, listOf("com.acme"))

        assertEquals(2, outcome?.unattributable)
        assertEquals(4, outcome?.captured)
    }

    // Which JVM each test ran in, and when each class first arrived there.

    private fun touches(mapDir: File, worker: String, vararg lines: String) {
        File(CoverageDecoder.recordsDir(mapDir), "worker-$worker/${AgentContract.TOUCHES_FILE}")
            .writeText(lines.joinToString("") { "$it\n" })
    }

    private fun firstTouches(dir: File): Map<String, Int> =
        File(dir, AgentContract.FIRST_TOUCH_FILE).readLines().filter(String::isNotBlank)
            .associate { line -> Tsv.split(line).let { it[2] to it[1].toInt() } }

    private val a = Triple("[class:A]/[method:a()]", "SUCCESSFUL", execData("com.acme.A" to booleanArrayOf(true)))
    private val b = Triple("[class:B]/[method:b()]", "SUCCESSFUL", execData("com.acme.B" to booleanArrayOf(true)))

    @Test
    fun `each test is placed at its sequence in its worker's JVM`(@TempDir dir: File) {
        records(dir, "1", a, b)
        touches(dir, "1", AgentContract.TOUCHES_COMPLETE)

        CoverageDecoder.decode(dir, listOf("com.acme"))

        val rows = File(dir, AgentContract.POSITIONS_FILE).readLines().map(Tsv::split)
        assertEquals(listOf("1" to "[class:A]/[method:a()]", "2" to "[class:B]/[method:b()]"), rows.map { it[1] to it[2] })
        assertEquals(1, rows.map { it[0] }.distinct().size, "one worker is one JVM")
        assertEquals(mapOf("com.acme.A" to 1, "com.acme.B" to 2), firstTouches(dir))
    }

    @Test
    fun `a load or a class-file read before a record dates the class at that record`(@TempDir dir: File) {
        records(dir, "1", a, b)
        touches(
            dir, "1",
            "1\t${AgentContract.TOUCH_LOADED}\tcom.acme.Point",
            "1\t${AgentContract.TOUCH_READ}\t/work/build/classes/java/main/com/acme/Codec.class",
            "2\t${AgentContract.TOUCH_READ}\tcom/acme/Other.class",
            "2\t${AgentContract.TOUCH_READ}\t/work/src/main/java/com/acme/Source.java",
            "2\t${AgentContract.TOUCH_LOADED}\torg.elsewhere.OutOfScope",
            AgentContract.TOUCHES_COMPLETE,
        )

        CoverageDecoder.decode(dir, listOf("com.acme"))

        val touched = firstTouches(dir)
        assertEquals(1, touched["com.acme.Point"])
        assertEquals(1, touched["com.acme.Codec"])
        assertEquals(2, touched["com.acme.Other"])
        assertEquals(2, touched["com.acme.Source"])
        assertNull(touched["org.elsewhere.OutOfScope"])
    }

    @Test
    fun `touches without their end marker touch every class from the first record`(@TempDir dir: File) {
        records(dir, "1", a, b)
        touches(dir, "1", "2\t${AgentContract.TOUCH_LOADED}\tcom.acme.Point")

        CoverageDecoder.decode(dir, listOf("com.acme"))

        assertEquals(0, firstTouches(dir)[AgentContract.FIRST_TOUCH_ANY])
    }

    @Test
    fun `a worker with no touches file touches every class from the first record`(@TempDir dir: File) {
        records(dir, "1", a, b)

        CoverageDecoder.decode(dir, listOf("com.acme"))

        assertEquals(0, firstTouches(dir)[AgentContract.FIRST_TOUCH_ANY])
    }

    @Test
    fun `an observation the agent called incomplete touches every class from there`(@TempDir dir: File) {
        records(dir, "1", a, b)
        touches(dir, "1", "2\t${AgentContract.TOUCH_ALL}\tthe read hooks could not be installed", AgentContract.TOUCHES_COMPLETE)

        CoverageDecoder.decode(dir, listOf("com.acme"))

        assertEquals(2, firstTouches(dir)[AgentContract.FIRST_TOUCH_ANY])
    }

    @Test
    fun `a jar a scanner opened itself that cannot be listed touches every class`(@TempDir dir: File) {
        records(dir, "1", a, b)
        touches(dir, "1", "2\t${AgentContract.TOUCH_JAR}\t${File(dir, "gone.jar").absolutePath}", AgentContract.TOUCHES_COMPLETE)

        CoverageDecoder.decode(dir, listOf("com.acme"))

        assertEquals(2, firstTouches(dir)[AgentContract.FIRST_TOUCH_ANY])
    }

    @Test
    fun `a recaptured test replaces its position and a retained one keeps its JVM's touches`(@TempDir dir: File) {
        records(dir, "1", a, b)
        touches(dir, "1", "1\t${AgentContract.TOUCH_LOADED}\tcom.acme.Point", AgentContract.TOUCHES_COMPLETE)
        CoverageDecoder.decode(dir, listOf("com.acme"))
        val firstJvm = Tsv.split(File(dir, AgentContract.POSITIONS_FILE).readLines().first())[0]

        CoverageDecoder.recordsDir(dir).deleteRecursively()
        records(dir, "1", a)
        touches(dir, "1", AgentContract.TOUCHES_COMPLETE)
        CoverageDecoder.decode(dir, listOf("com.acme"), selecting = true)

        val positions = File(dir, AgentContract.POSITIONS_FILE).readLines().map(Tsv::split)
        assertEquals(firstJvm, positions.single { it[2] == "[class:B]/[method:b()]" }[0], "B was not recaptured")
        assertTrue(positions.single { it[2] == "[class:A]/[method:a()]" }[0] != firstJvm, "A was recaptured")
        val touchRows = File(dir, AgentContract.FIRST_TOUCH_FILE).readLines().map(Tsv::split)
        assertTrue(touchRows.any { it[0] == firstJvm && it[2] == "com.acme.Point" }, "B's JVM lost its touches")
    }

    @Test
    fun `a capture that dates the map drops every test record it did not re-observe`(@TempDir dir: File) {
        // A test this capture did not report (its class's setup failed, it was deleted, a filter
        // left it out) would otherwise keep an older SUCCESSFUL record under a newer stamp, and the
        // next run would skip it. Dropped, it is not in the map, and a test not in the map runs.
        records(dir, "1", a, b)
        touches(dir, "1", "2\t${AgentContract.TOUCH_LOADED}\tcom.acme.Point", AgentContract.TOUCHES_COMPLETE)
        CoverageDecoder.decode(dir, listOf("com.acme"), datesTheMap = true)
        val firstJvm = Tsv.split(File(dir, AgentContract.POSITIONS_FILE).readLines().first())[0]

        CoverageDecoder.recordsDir(dir).deleteRecursively()
        records(dir, "1", a)
        touches(dir, "1", AgentContract.TOUCHES_COMPLETE)
        val outcome = CoverageDecoder.decode(dir, listOf("com.acme"), datesTheMap = true)

        assertEquals(1, outcome?.total)
        val coverage = File(dir, AgentContract.COVERAGE_FILE).readText()
        assertFalse(coverage.contains("[class:B]"), "a record this capture never saw survived it: $coverage")
        // The first-touch tables speak for the records the map holds, and no others.
        val positions = File(dir, AgentContract.POSITIONS_FILE).readLines().map(Tsv::split)
        assertEquals(listOf("[class:A]/[method:a()]"), positions.map { it[2] })
        for (name in listOf(AgentContract.FIRST_TOUCH_FILE, AgentContract.NAMED_TOUCH_FILE, AgentContract.JVM_MODE_FILE)) {
            val rows = File(dir, name).readLines().filter(String::isNotBlank).map(Tsv::split)
            assertFalse(rows.any { it[0] == firstJvm }, "$name kept a row of a JVM whose records are gone")
        }
    }

    @Test
    fun `a selecting capture that dates the map still keeps the startup coverage it did not replace`(
        @TempDir dir: File,
    ) {
        // Startup coverage forces a full run when touched; dropping it only ever narrows.
        val unattributed = "[yoriwake:unattributed]"
        records(dir, "1", Triple(unattributed, "NONE", execData("com.acme.Startup" to booleanArrayOf(true))), a)
        CoverageDecoder.decode(dir, listOf("com.acme"), datesTheMap = true)
        CoverageDecoder.recordsDir(dir).deleteRecursively()

        records(dir, "1", Triple(unattributed, "NONE", execData("com.acme.Boot" to booleanArrayOf(true))), a)
        CoverageDecoder.decode(dir, listOf("com.acme"), selecting = true, datesTheMap = true)

        val text = File(dir, AgentContract.COVERAGE_FILE).readText()
        assertContains(text, "com.acme.Startup")
        assertContains(text, "com.acme.Boot")
    }

    @Test
    fun `an attributable test with an unreadable blob is recorded UNKNOWN over its older record`(@TempDir dir: File) {
        // Dropped, its id would not supersede the older SUCCESSFUL record, and a test that may just
        // have failed would read as known to pass.
        records(dir, "1", a)
        CoverageDecoder.decode(dir, listOf("com.acme"))
        CoverageDecoder.recordsDir(dir).deleteRecursively()

        records(dir, "1", Triple(a.first, "FAILED", "not jacoco data".toByteArray()))
        CoverageDecoder.decode(dir, listOf("com.acme"))

        val line = File(dir, AgentContract.COVERAGE_FILE).readLines().single(String::isNotBlank)
        assertEquals(Tsv.join(AgentContract.OUTCOME_UNKNOWN, "1000000", "", a.first), line)
        assertEquals(listOf(a.first), File(dir, AgentContract.POSITIONS_FILE).readLines().map { Tsv.split(it)[2] })
    }

    @Test
    fun `an unreadable window no test owns keeps its older coverage through a capture that dates the map`(
        @TempDir dir: File,
    ) {
        // Startup and class-scoped coverage force or select when touched. With the window's blob
        // unreadable, the capture has nothing to replace them with, and dropping the older records
        // (or letting the window's readable siblings supersede them) would only narrow.
        val window = "[yoriwake:class][engine:junit-jupiter]/[class:com.acme.ATest]"
        val unattributed = "[yoriwake:unattributed]"
        records(
            dir, "1",
            Triple(unattributed, "NONE", execData("com.acme.Startup" to booleanArrayOf(true))),
            Triple(window, "NONE", execData("com.acme.Setup" to booleanArrayOf(true))),
            a,
        )
        CoverageDecoder.decode(dir, listOf("com.acme"), datesTheMap = true)
        CoverageDecoder.recordsDir(dir).deleteRecursively()

        val unreadable = "not jacoco data".toByteArray()
        records(
            dir, "1",
            Triple(unattributed, "NONE", unreadable),
            Triple(unattributed, "NONE", execData("com.acme.Boot" to booleanArrayOf(true))),
            Triple(window, "NONE", unreadable),
            a,
        )
        CoverageDecoder.decode(dir, listOf("com.acme"), datesTheMap = true)

        val text = File(dir, AgentContract.COVERAGE_FILE).readText()
        assertContains(text, "com.acme.Startup")
        assertContains(text, "com.acme.Boot")
        assertContains(text, "com.acme.Setup")
    }

    @Test
    fun `a class file path names the class at every suffix, so the scope keeps the real one`() {
        assertEquals(
            listOf("x.main.com.acme.A", "main.com.acme.A", "com.acme.A", "acme.A", "A"),
            CoverageDecoder.namesForPath("x/main/com/acme/A.class"),
        )
        assertEquals(listOf("com.acme.A\$B", "acme.A\$B", "A\$B"), CoverageDecoder.namesForPath("com\\acme\\A\$B.class"))
        assertEquals(emptyList(), CoverageDecoder.namesForPath("com/acme/notes.txt"))
    }

    // What dates a test class nothing else names: lookups and reads after discovery, and
    // executions in another class's window.

    private fun namedTouches(dir: File): Map<String, Int> =
        File(dir, AgentContract.NAMED_TOUCH_FILE).readLines().filter(String::isNotBlank)
            .associate { line -> Tsv.split(line).let { it[2] to it[1].toInt() } }

    private val jupiterA = Triple("[engine:junit-jupiter]/[class:com.acme.ATest]/[method:a()]", "SUCCESSFUL",
        execData("com.acme.ATest" to booleanArrayOf(true), "com.acme.BTest" to booleanArrayOf(true)))
    private val jupiterB = Triple("[engine:junit-jupiter]/[class:com.acme.BTest]/[method:b()]", "SUCCESSFUL",
        execData("com.acme.BTest" to booleanArrayOf(true)))

    @Test
    fun `lookups and reads during discovery do not date a test class, and those after do`(@TempDir dir: File) {
        records(dir, "1", jupiterB, jupiterA)
        touches(
            dir, "1",
            "1\t${AgentContract.TOUCH_LOOKUP}\tcom.acme.ATest",
            "1\t${AgentContract.TOUCH_PLAN_STARTED}\t",
            "2\t${AgentContract.TOUCH_LOOKUP}\tcom.acme.CTest",
            "2\t${AgentContract.TOUCH_READ}\tcom/acme/DTest.class",
            AgentContract.TOUCHES_COMPLETE,
        )

        CoverageDecoder.decode(dir, listOf("com.acme"))

        val named = namedTouches(dir)
        assertNull(named["com.acme.ATest"], "discovery looked ATest up")
        assertEquals(2, named["com.acme.CTest"])
        assertEquals(2, named["com.acme.DTest"])
        // ATest's own record executed BTest: BTest was reached from outside its own tests.
        assertEquals(2, named["com.acme.BTest"])
    }

    @Test
    fun `a class another loader defined dates it as a lookup would, and a child process dates everything`(
        @TempDir dir: File,
    ) {
        records(dir, "1", jupiterB, jupiterA)
        touches(
            dir, "1",
            "1\t${AgentContract.TOUCH_DEFINED}\tcom.acme.ATest",
            "1\t${AgentContract.TOUCH_PLAN_STARTED}\t",
            "2\t${AgentContract.TOUCH_DEFINED}\tcom.acme.CTest",
            "2\t${AgentContract.TOUCH_ALL}\ta child process was started",
            AgentContract.TOUCHES_COMPLETE,
        )

        CoverageDecoder.decode(dir, listOf("com.acme"))

        val named = namedTouches(dir)
        assertNull(named["com.acme.ATest"], "discovery defined ATest")
        assertEquals(2, named["com.acme.CTest"])
        assertEquals(2, firstTouches(dir)["com.acme.CTest"])
        assertEquals(2, firstTouches(dir)[AgentContract.FIRST_TOUCH_ANY])
        assertEquals(2, named[AgentContract.FIRST_TOUCH_ANY])
    }

    @Test
    fun `a looked-up name that starts with L is kept whole, and only a descriptor is unwrapped`(@TempDir dir: File) {
        records(dir, "1", jupiterB, jupiterA)
        touches(
            dir, "1",
            "1\t${AgentContract.TOUCH_PLAN_STARTED}\t",
            "2\t${AgentContract.TOUCH_LOOKUP}\tLoginTest",
            "2\t${AgentContract.TOUCH_LOOKUP}\t[LLexerTest;",
            "2\t${AgentContract.TOUCH_LOOKUP}\t[[Lcom.acme.LTest;",
            "2\t${AgentContract.TOUCH_LOOKUP}\tLcom/acme/Ledger;",
            "2\t${AgentContract.TOUCH_LOOKUP}\tcom/acme/Outer\$Inner",
            AgentContract.TOUCHES_COMPLETE,
        )

        // No package scope, as for a build with classes in the default package.
        CoverageDecoder.decode(dir, emptyList())

        val named = namedTouches(dir)
        assertEquals(2, named["LoginTest"], "named $named")
        assertEquals(2, named["LexerTest"], "named $named")
        assertEquals(2, named["com.acme.LTest"], "named $named")
        assertEquals(2, named["com.acme.Ledger"], "named $named")
        assertEquals(2, named["com.acme.Outer\$Inner"], "named $named")
    }

    @Test
    fun `touches that never marked the plan's start date every test class from the start`(@TempDir dir: File) {
        records(dir, "1", jupiterB)
        touches(dir, "1", "1\t${AgentContract.TOUCH_LOOKUP}\tcom.acme.ATest", AgentContract.TOUCHES_COMPLETE)

        CoverageDecoder.decode(dir, listOf("com.acme"))

        assertEquals(0, namedTouches(dir)[AgentContract.FIRST_TOUCH_ANY])
    }

    @Test
    fun `a lookup the agent could not observe dates every test class from there`(@TempDir dir: File) {
        records(dir, "1", jupiterB, jupiterA)
        touches(
            dir, "1",
            "1\t${AgentContract.TOUCH_PLAN_STARTED}\t",
            "2\t${AgentContract.TOUCH_LOOKUP}\t${AgentContract.FIRST_TOUCH_ANY}",
            AgentContract.TOUCHES_COMPLETE,
        )

        CoverageDecoder.decode(dir, listOf("com.acme"))

        assertEquals(2, namedTouches(dir)[AgentContract.FIRST_TOUCH_ANY])
    }

    @Test
    fun `a JVM that ran an engine not known to own its classes dates every test class from the start`(
        @TempDir dir: File,
    ) {
        records(
            dir, "1", jupiterB,
            Triple("[engine:cucumber]/[feature:a]/[scenario:1]", "SUCCESSFUL", execData("com.acme.Glue" to booleanArrayOf(true))),
        )
        touches(dir, "1", "1\t${AgentContract.TOUCH_PLAN_STARTED}\t", AgentContract.TOUCHES_COMPLETE)

        CoverageDecoder.decode(dir, listOf("com.acme"))

        assertEquals(0, namedTouches(dir)[AgentContract.FIRST_TOUCH_ANY])
    }

    // How each JVM was recorded. Only a JVM that ran tests of one class is isolated, whatever the
    // run asked for; everything else, including a JVM the file does not mention, is shared.

    private fun jvmModes(dir: File): Map<String, String> =
        File(dir, AgentContract.JVM_MODE_FILE).readLines().filter(String::isNotBlank)
            .associate { line -> Tsv.split(line).let { it[0].substringAfter('/') to it[1] } }

    @Test
    fun `an isolated capture labels a JVM that ran one test class isolated and one that ran two shared`(
        @TempDir dir: File,
    ) {
        records(dir, "1", jupiterA)
        records(dir, "2", jupiterB, jupiterA)

        CoverageDecoder.decode(dir, listOf("com.acme"), isolated = true)

        assertEquals(
            mapOf("worker-1" to AgentContract.MODE_ISOLATED, "worker-2" to AgentContract.MODE_SHARED),
            jvmModes(dir),
        )
        assertEquals(CoverageDecoder.MIXED_MODES, CoverageDecoder.captureMode(dir))
    }

    @Test
    fun `a capture that did not ask for isolation labels even a one-class JVM shared`(@TempDir dir: File) {
        records(dir, "1", jupiterA)

        CoverageDecoder.decode(dir, listOf("com.acme"))

        assertEquals(mapOf("worker-1" to AgentContract.MODE_SHARED), jvmModes(dir))
        assertEquals(AgentContract.MODE_SHARED, CoverageDecoder.captureMode(dir))
    }

    @Test
    fun `a JVM that ran a test naming no class is not labelled isolated`(@TempDir dir: File) {
        records(dir, "1", Triple("[engine:spock]/[spec:com.acme.ASpec]/[feature:f]", "SUCCESSFUL",
            execData("com.acme.A" to booleanArrayOf(true))))

        CoverageDecoder.decode(dir, listOf("com.acme"), isolated = true)

        assertEquals(mapOf("worker-1" to AgentContract.MODE_SHARED), jvmModes(dir))
    }

    @Test
    fun `a merge keeps the mode of every JVM a retained test ran in`(@TempDir dir: File) {
        records(dir, "1", jupiterA)
        records(dir, "2", jupiterB)
        CoverageDecoder.decode(dir, listOf("com.acme"), isolated = true)
        assertEquals(AgentContract.MODE_ISOLATED, CoverageDecoder.captureMode(dir))

        CoverageDecoder.recordsDir(dir).deleteRecursively()
        records(dir, "1", jupiterA)
        CoverageDecoder.decode(dir, listOf("com.acme"), selecting = true)

        val rows = File(dir, AgentContract.JVM_MODE_FILE).readLines().map(Tsv::split)
        val positions = File(dir, AgentContract.POSITIONS_FILE).readLines().map(Tsv::split)
        val jvmOfB = positions.single { it[2] == jupiterB.first }[0]
        assertEquals(AgentContract.MODE_ISOLATED, rows.single { it[0] == jvmOfB }[1], "B's JVM lost its mode")
        assertEquals(CoverageDecoder.MIXED_MODES, CoverageDecoder.captureMode(dir))
    }

    @Test
    fun `a JVM the mode file does not name, or names with another word, reads as shared`(@TempDir dir: File) {
        records(dir, "1", jupiterA)
        CoverageDecoder.decode(dir, listOf("com.acme"), isolated = true)
        val modes = File(dir, AgentContract.JVM_MODE_FILE)
        assertEquals(AgentContract.MODE_ISOLATED, CoverageDecoder.captureMode(dir))

        modes.writeText(modes.readText().replace(AgentContract.MODE_ISOLATED, "ISOLATED"))
        assertEquals(AgentContract.MODE_SHARED, CoverageDecoder.captureMode(dir))

        modes.delete()
        assertEquals(AgentContract.MODE_SHARED, CoverageDecoder.captureMode(dir))
    }
}
