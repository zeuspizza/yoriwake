package io.github.zeuspizza.yoriwake.agent.platform

import io.github.zeuspizza.yoriwake.agent.contract.AgentContract
import io.github.zeuspizza.yoriwake.agent.select.Selector
import io.github.zeuspizza.yoriwake.agent.select.Verdict
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The per-test decision, as a file: JUnit puts a `PostDiscoveryFilter`'s reason in no artifact,
 * and the result XML shows what ran but never why anything did not.
 *
 * The worst failure is a run that writes nothing or leaves the previous run's file in place, so the
 * empty and stale cases come first.
 */
class DecisionRecordTest {

    private fun read(dir: File) = File(dir, AgentContract.DECISIONS_FILE).readText()

    private fun rows(dir: File) = read(dir).lines()
        .filter { it.isNotBlank() && !it.startsWith("#") }

    @Test
    fun `six records each writing once, the shape a real test JVM produces`(@TempDir dir: File) {
        // The Platform can make several discovery requests per JVM, each with its own record
        // writing at shutdown.
        val records = (1..6).map { n ->
            DecisionRecord().apply { repeat(n * 50) { add("t$n-$it:test", Verdict.REACHES_CHANGE) } }
        }
        val captured = java.io.ByteArrayOutputStream()
        val original = System.out
        val start = java.util.concurrent.CountDownLatch(1)
        try {
            System.setOut(java.io.PrintStream(captured, true))
            val threads = records.map { record ->
                Thread { start.await(); record.writeTo(dir) }.apply { start() }
            }
            start.countDown()
            threads.forEach { it.join() }
        } finally {
            System.setOut(original)
        }

        val complaints = captured.toString().lines().filter { it.contains("could not write") }
        assertTrue(complaints.isEmpty(), "${complaints.size} of 6 writers failed: ${complaints.take(2)}")
    }

    @Test
    fun `every writer's whole answer survives in its own part, whoever wins the shared file`(
        @TempDir dir: File,
    ) {
        // With `forkEvery`, several JVMs write `decisions.tsv` and the survivor holds only its own
        // rows, so each writer's answer must survive in a part of its own.
        val records = (1..3).map { n ->
            DecisionRecord().apply { repeat(n) { add("w$n-$it:test", Verdict.NOT_IN_MAP) } }
        }
        records.forEach { it.writeTo(dir) }
        records.first().writeTo(dir) // a second write of one record replaces its own part

        val parts = dir.listFiles { f -> f.name.endsWith(AgentContract.DECISIONS_PART_SUFFIX) }!!.sortedBy { it.name }
        assertEquals(3, parts.size, "one part per writer: ${parts.map { it.name }}")
        val partRows = parts.map { part -> part.readLines().filter { it.isNotEmpty() && !it.startsWith("#") } }
        assertEquals(listOf(1, 2, 3), partRows.map { it.size }.sorted())
        assertEquals(6, partRows.flatten().toSet().size)
        assertTrue(parts.all { it.name.startsWith(AgentContract.DECISIONS_FILE + ".") })
        assertTrue(dir.listFiles { f -> f.name.endsWith(".writing") }!!.isEmpty())
        // The shared file is still one writer's whole answer, for every reader that wants that.
        assertEquals(1, rows(dir).count { it.startsWith("w1-") })
    }

    @Test
    fun `two records writing one directory at once do not fight over one staging file`(
        @TempDir dir: File,
    ) {
        // Several records in one JVM write from shutdown hooks at the same time, so a staging path
        // per process collides. The error line is the deterministic symptom; a blended file also
        // needs interleaved flushes, which a small payload may not produce.
        val many = DecisionRecord().apply {
            repeat(2_000) { add("test$it:test", Verdict.REACHES_CHANGE) }
        }
        val empty = DecisionRecord()
        val captured = java.io.ByteArrayOutputStream()
        val original = System.out
        val start = java.util.concurrent.CountDownLatch(1)
        try {
            System.setOut(java.io.PrintStream(captured, true))
            val threads = listOf(many, empty).map { record ->
                Thread {
                    start.await()
                    repeat(60) { record.writeTo(dir) }
                }.apply { start() }
            }
            start.countDown()
            threads.forEach { it.join() }
        } finally {
            System.setOut(original)
        }

        val complaints = captured.toString().lines().filter { it.contains("could not write") }
        assertTrue(
            complaints.isEmpty(),
            "two writers in one JVM fought over one staging file: ${complaints.take(2)}",
        )
        val text = File(dir, AgentContract.DECISIONS_FILE).readText()
        val declared = text.lines()
            .first { it.startsWith("${AgentContract.NOTE_PREFIX}${AgentContract.ROWS_NOTE}") }
            .substringAfterLast('\t').trim().toInt()
        assertEquals(
            declared,
            rows(dir).size,
            "the surviving file declares $declared rows and carries ${rows(dir).size}",
        )
    }

    @Test
    fun `a run that decided nothing still writes a file, and it is legibly empty`(@TempDir dir: File) {
        DecisionRecord().writeTo(dir)

        // A missing file cannot be told from a run where the agent never loaded.
        assertTrue(File(dir, AgentContract.DECISIONS_FILE).isFile, "no decision file was written at all")
        assertEquals(emptyList(), rows(dir))
        assertTrue(read(dir).startsWith("#"), "an empty record must still carry its header")
    }

    @Test
    fun `writing replaces the previous run's rows rather than appending to them`(@TempDir dir: File) {
        DecisionRecord().apply { add("old:test", Verdict.SKIPPED) }.writeTo(dir)
        DecisionRecord().apply { add("new:test", Verdict.REACHES_CHANGE) }.writeTo(dir)

        val rows = rows(dir)
        assertEquals(1, rows.size, "the previous run's rows survived: $rows")
        assertTrue(rows.single().startsWith("new:test"), rows.single())
        assertFalse(read(dir).contains("old:test"), read(dir))
    }

    @Test
    fun `a write that cannot happen never reaches the caller`(@TempDir dir: File) {
        // This runs inside a host's test JVM; a diagnostic artifact must never fail the build.
        val notADirectory = File(dir, "occupied").apply { writeText("I am a file") }

        DecisionRecord().apply { add("a:test", Verdict.NOT_IN_MAP) }.writeTo(notADirectory)
    }

    @Test
    fun `each decision is one row of id, verdict and reason`(@TempDir dir: File) {
        DecisionRecord().apply {
            add("[engine:junit-jupiter]/[class:AlphaTest]/[method:passes()]", Verdict.SKIPPED)
            add("[engine:junit-jupiter]/[class:BetaTest]/[method:passes()]", Verdict.REACHES_CHANGE)
        }.writeTo(dir)

        assertEquals(
            listOf(
                "[engine:junit-jupiter]/[class:AlphaTest]/[method:passes()]\texcluded\tSKIPPED",
                "[engine:junit-jupiter]/[class:BetaTest]/[method:passes()]\tincluded\tREACHES_CHANGE",
            ),
            rows(dir),
        )
    }

    @Test
    fun `rows keep the order they were decided in`(@TempDir dir: File) {
        DecisionRecord().apply {
            add("c", Verdict.REACHES_CHANGE); add("a", Verdict.REACHES_CHANGE); add("b", Verdict.REACHES_CHANGE)
        }.writeTo(dir)

        assertEquals(listOf("c", "a", "b"), rows(dir).map { it.substringBefore('\t') })
    }

    @Test
    fun `a tab or a newline in an id cannot forge a row`(@TempDir dir: File) {
        // A display name is host-supplied: unescaped, a newline would split a row and a tab would
        // shift every field right of it.
        DecisionRecord().apply {
            add("odd\tid\nwith breaks", Verdict.NOT_IN_MAP)
        }.writeTo(dir)

        val rows = rows(dir)
        assertEquals(1, rows.size, "an escaped id still produced more than one row: $rows")
        assertEquals(3, rows.single().split("\t").size, "field count changed: ${rows.single()}")
        assertEquals("odd\\tid\\nwith breaks", rows.single().substringBefore('\t'))
    }

    @Test
    fun `the header names the columns, so the file explains itself`(@TempDir dir: File) {
        DecisionRecord().apply { add("a", Verdict.REACHES_CHANGE) }.writeTo(dir)

        val header = read(dir).lineSequence().first()
        assertTrue(header.contains("test"), header)
        assertTrue(header.contains("verdict"), header)
        assertTrue(header.contains("reason"), header)
    }

    @Test
    fun `the reason is written as the token, never as prose`(@TempDir dir: File) {
        // Scripts branch on the token; substring-matching English breaks on a reworded message.
        Selector.Decision.Reason.values().forEach { reason ->
            val record = DecisionRecord().apply { add("t", Verdict.of(reason)) }
            record.writeTo(dir)
            assertTrue(rows(dir).single().endsWith("\t${reason.name}"), rows(dir).single())
        }
    }

    private fun notes(dir: File) = read(dir).lines()
        .filter { it.startsWith(AgentContract.NOTE_PREFIX) }
        .associate {
            val (key, value) = it.removePrefix(AgentContract.NOTE_PREFIX).split("\t", limit = 2)
            key to value
        }

    @Test
    fun `why a run selected nothing is recorded, because no row can say it`(@TempDir dir: File) {
        // Why a run executed everything belongs to the decision, not to any test; every row just
        // says `included/FULL_RUN`.
        DecisionRecord().apply {
            note("outcome", "full-run")
            note("full-run-kind", "no-coverage-for-changed")
            note("full-run-reason", "the map has no coverage for p.Changed")
            add("t", Verdict.FULL_RUN)
        }.writeTo(dir)

        assertEquals("no-coverage-for-changed", notes(dir)["full-run-kind"])
        assertEquals("full-run", notes(dir)["outcome"])
        // The rows are untouched by the notes, or every existing reader of this file breaks.
        assertEquals(listOf("t\tincluded\tFULL_RUN"), rows(dir))
    }

    @Test
    fun `a note is machine-readable, so a reader never substring-matches the header`(@TempDir dir: File) {
        DecisionRecord().apply { note("outcome", "narrowed") }.writeTo(dir)

        val lines = read(dir).lines().filter { it.isNotBlank() }
        assertTrue(lines.first().startsWith("# test"), "the human header must still come first")
        assertTrue(lines.any { it == "${AgentContract.NOTE_PREFIX}outcome\tnarrowed" }, read(dir))
        // A plain `#` would be indistinguishable from the header, so a reader skipping comments
        // would skip the notes too.
        assertFalse(lines.any { it.startsWith("# outcome") }, read(dir))
    }

    @Test
    fun `an absent value is left out rather than written blank`(@TempDir dir: File) {
        // Empty is not unknown. A `full-run-kind` of "" would read as a kind the reader does not
        // recognise; absent reads as a question that was not answered.
        DecisionRecord().apply {
            note("outcome", "full-run")
            note("full-run-kind", null)
            note("full-run-reason", "")
        }.writeTo(dir)

        // The writer's own two notes are always there and are not the caller's; what this pins is
        // that a null and an empty value contributed nothing.
        assertEquals(
            mapOf(
                AgentContract.VERSION_NOTE to AgentContract.DECISIONS_VERSION,
                AgentContract.ROWS_NOTE to "0",
                "outcome" to "full-run",
            ),
            // The writer note is dropped here, not asserted: its value counts every record this
            // JVM has made, so it is stable in a test JVM only by accident. Its own case pins it.
            notes(dir) - AgentContract.WRITER_NOTE,
        )
    }

    @Test
    fun `a reason containing a tab or a newline cannot forge a note`(@TempDir dir: File) {
        // A full-run reason can name a class, and a class name is host-supplied. Unescaped, a
        // newline here would split into a second note line and a reader would believe it.
        DecisionRecord().apply {
            note("full-run-reason", "broke\non\ttwo")
        }.writeTo(dir)

        // Three notes: the version, the row count, and the one this test wrote. The forged fourth
        // and fifth a raw newline would have produced are what must not appear.
        assertEquals(4, read(dir).lines().count { it.startsWith(AgentContract.NOTE_PREFIX) })
        assertEquals("broke\\non\\ttwo", notes(dir)["full-run-reason"])
        // And it comes back as what went in, through the inverse the reader is written against.
        assertEquals("broke\non\ttwo", io.github.zeuspizza.yoriwake.agent.contract.Tsv.unescape(notes(dir)["full-run-reason"]!!))
    }

    @Test
    fun `the record says which of this JVM's writers survived`(@TempDir dir: File) {
        // The file is one discovery request's answer, and last writer wins. A reader holding it
        // needs to know that before comparing its row count with the number of tests the task ran.
        DecisionRecord().apply { add("a:test", Verdict.REACHES_CHANGE) }.writeTo(dir)

        val writer = notes(dir)[AgentContract.WRITER_NOTE]
        assertTrue(
            writer != null && Regex("""\d+ of \d+""").matches(writer),
            "the writer note should read `<n> of <m>`, got $writer",
        )
    }

    @Test
    fun `the record says which version wrote it and how many rows it meant to hold`(@TempDir dir: File) {
        // Lets a reader tell apart a token this jar predates, a token the run never produced, and a
        // file whose tail was lost -- real, since it is written from a shutdown hook.
        DecisionRecord().apply {
            add("a", Verdict.REACHES_CHANGE)
            add("b", Verdict.SKIPPED)
        }.writeTo(dir)

        val written = notes(dir)
        assertEquals(AgentContract.DECISIONS_VERSION, written[AgentContract.VERSION_NOTE])
        assertEquals("2", written[AgentContract.ROWS_NOTE], "the count must match the rows written")
        assertEquals(2, read(dir).lines().count { !it.startsWith("#") && it.isNotBlank() })
        // The count is written among the notes, which precede the rows: a reader that has parsed
        // the header already knows how many rows to expect before it fails to find them.
        val lines = read(dir).lines()
        assertTrue(
            lines.indexOfFirst { it.startsWith(AgentContract.NOTE_PREFIX + AgentContract.ROWS_NOTE) }
                < lines.indexOfFirst { !it.startsWith("#") && it.isNotBlank() },
        )
    }

    @Test
    fun `a record with no rows is still written, so a run that discovered nothing says why`(
        @TempDir dir: File,
    ) {
        // A refused run that discovers no tests never calls the filter, and its record is the only
        // thing that can say the daemon refused. Written with a header, both notes and no rows.
        DecisionRecord().apply { note("outcome", "full-run") }.writeTo(dir)

        val written = notes(dir)
        assertEquals("0", written[AgentContract.ROWS_NOTE])
        assertEquals("full-run", written["outcome"])
    }

    @Test
    fun `the staging file is not shared between JVMs`(@TempDir dir: File) {
        // maxParallelForks and forkEvery both put several test JVMs against one map directory. A
        // shared staging name means two of them writing one file and moving each other's half.
        DecisionRecord().apply { add("a", Verdict.REACHES_CHANGE) }.writeTo(dir)

        assertTrue(File(dir, AgentContract.DECISIONS_FILE).isFile)
        assertEquals(
            emptyList(), dir.list()!!.filter { it.endsWith(".writing") },
            "a staging file survived the write",
        )
    }

    @Test
    fun `every full-run kind survives the round trip as its token`(@TempDir dir: File) {
        // The tokens are the contract every reader of the record keys on. A kind whose token did
        // not survive would arrive in a results file as a force reason nobody can act on.
        Selector.Decision.FullRunKind.values().forEach { kind ->
            DecisionRecord().apply { note("full-run-kind", kind.token()) }.writeTo(dir)
            assertEquals(kind.token(), notes(dir)["full-run-kind"])
        }
    }

    @Test
    fun `counting rows by verdict answers the question the result XML cannot`(@TempDir dir: File) {
        DecisionRecord().apply {
            add("a", Verdict.SKIPPED)
            add("b", Verdict.SKIPPED)
            add("c", Verdict.REACHES_CHANGE)
        }.writeTo(dir)

        val byVerdict = rows(dir).groupingBy { it.split("\t")[1] }.eachCount()
        assertEquals(mapOf("excluded" to 2, "included" to 1), byVerdict)
    }

    /** The rules one change makes on a two-test map: AlphaTest reaches it, BetaTest does not. */
    private fun rules(dir: File, refused: Boolean = false): io.github.zeuspizza.yoriwake.agent.select.Rules {
        val map = File(dir, "map").apply { mkdirs() }
        File(map, AgentContract.COVERAGE_FILE).writeText(
            "SUCCESSFUL\t1\tdev.Alpha\tAlphaTest\nSUCCESSFUL\t1\tdev.Beta\tBetaTest\n",
        )
        File(map, AgentContract.POSITIONS_FILE).writeText("a\t1\tAlphaTest\nb\t1\tBetaTest\n")
        File(map, AgentContract.FIRST_TOUCH_FILE).writeText("")
        File(map, AgentContract.NAMED_TOUCH_FILE).writeText("")
        File(map, AgentContract.MAP_SCHEMA_VERSION_FILE).writeText("${AgentContract.MAP_SCHEMA_VERSION}\n")
        val change = io.github.zeuspizza.yoriwake.agent.select.ChangeSet.of(listOf("dev.Alpha"), emptyList())
            .let { if (refused) it.withDaemonRefusal("refused") else it }
        return Selector.rules(io.github.zeuspizza.yoriwake.agent.select.MapReader.read(map), change, true)
    }

    @Test
    fun `every rule behind each row follows the rows, and leaves the rows as they were`(@TempDir dir: File) {
        DecisionRecord().apply {
            add("AlphaTest", Verdict.REACHES_CHANGE)
            add("BetaTest", Verdict.SKIPPED)
            add("NewTest", Verdict.NOT_IN_MAP)
        }.writeTo(dir, rules(dir))

        assertEquals(
            listOf("AlphaTest\tincluded\tREACHES_CHANGE", "BetaTest\texcluded\tSKIPPED", "NewTest\tincluded\tNOT_IN_MAP"),
            rows(dir),
            "a rule line must not reach a reader of the rows",
        )
        val lines = read(dir).lines()
        val ruleLines = lines.filter { it.startsWith(AgentContract.RULES_LINE_PREFIX) }
        assertEquals(
            listOf("#+AlphaTest\treaches-change", "#+BetaTest\tnone", "#+NewTest\tnot-in-map"),
            ruleLines,
        )
        assertTrue(lines.indexOf(ruleLines.first()) > lines.indexOf("NewTest\tincluded\tNOT_IN_MAP"))
        assertEquals(AgentContract.RULES_COMPLETE, notes(dir)[AgentContract.RULES_NOTE])
        assertEquals(AgentContract.RULE_NONE, notes(dir)[AgentContract.FORCING_KINDS_NOTE])
    }

    @Test
    fun `a refused run's rows keep the refusal and its rule lines the selection beneath it`(@TempDir dir: File) {
        DecisionRecord().apply {
            add("AlphaTest", Verdict.DAEMON_REFUSED)
            add("BetaTest", Verdict.DAEMON_REFUSED)
        }.writeTo(dir, rules(dir, refused = true))

        assertEquals(listOf("AlphaTest\tincluded\tDAEMON_REFUSED", "BetaTest\tincluded\tDAEMON_REFUSED"), rows(dir))
        assertEquals(
            listOf("#+AlphaTest\treaches-change", "#+BetaTest\tnone"),
            read(dir).lines().filter { it.startsWith(AgentContract.RULES_LINE_PREFIX) },
        )
        assertEquals(AgentContract.RULES_FROM_REFUSED_INPUTS, notes(dir)[AgentContract.RULES_NOTE])
        assertEquals("daemon-refused", notes(dir)[AgentContract.FORCING_KINDS_NOTE])
    }

    @Test
    fun `a record with no rules writes no rule lines and no rule notes`(@TempDir dir: File) {
        DecisionRecord().apply { add("a", Verdict.SELECTION_NOT_REQUESTED) }.writeTo(dir, null)

        assertFalse(read(dir).lines().any { it.startsWith(AgentContract.RULES_LINE_PREFIX) }, read(dir))
        assertFalse(notes(dir).containsKey(AgentContract.RULES_NOTE))
        assertFalse(notes(dir).containsKey(AgentContract.FORCING_KINDS_NOTE))
    }

    @Test
    fun `a tab or a newline in an id cannot forge a rule line`(@TempDir dir: File) {
        DecisionRecord().apply { add("odd\tid\nwith breaks", Verdict.NOT_IN_MAP) }.writeTo(dir, rules(dir))

        val ruleLines = read(dir).lines().filter { it.startsWith(AgentContract.RULES_LINE_PREFIX) }
        assertEquals(listOf("#+odd\\tid\\nwith breaks\tnot-in-map"), ruleLines)
    }

    @Test
    fun `an observed verdict is a row of what ran, and a line of what selection would have done`(@TempDir dir: File) {
        DecisionRecord().apply {
            observe("a:test", Verdict.SKIPPED)
            observe("b:test", Verdict.REACHES_CHANGE)
        }.writeTo(dir)

        assertEquals(listOf("a:test\tincluded\tOBSERVING", "b:test\tincluded\tOBSERVING"), rows(dir))
        assertEquals(
            listOf("#?a:test\texcluded\tSKIPPED", "#?b:test\tincluded\tREACHES_CHANGE"),
            read(dir).lines().filter { it.startsWith(AgentContract.OBSERVATION_LINE_PREFIX) },
        )
    }

    @Test
    fun `an observed run's rule lines follow the verdict selection would have given`(@TempDir dir: File) {
        DecisionRecord().apply { observe("[engine:junit-jupiter]/[class:Pinned]/[method:t()]", Verdict.ALWAYS_RUN) }
            .writeTo(dir, rules(dir))

        val line = read(dir).lines().single { it.startsWith(AgentContract.RULES_LINE_PREFIX) }
        assertTrue(AgentContract.RULE_ALWAYS_RUN in line, line)
    }

    @Test
    fun `the observed outcome is one note of tab-separated fields, trailing blanks dropped`(@TempDir dir: File) {
        DecisionRecord().apply {
            noteFields(AgentContract.OBSERVED_OUTCOME_NOTE, AgentContract.RUN_FULL, "daemon-refused", "full-run-requested")
        }.writeTo(dir)
        DecisionRecord().apply { noteFields(AgentContract.OBSERVED_OUTCOME_NOTE, AgentContract.RUN_NARROWED, null, "") }
            .writeTo(File(dir, "narrowed"))

        assertTrue("#!observed-outcome\tfull-run\tdaemon-refused\tfull-run-requested" in read(dir).lines())
        assertTrue("#!observed-outcome\tnarrowed" in read(File(dir, "narrowed")).lines())
    }
    @Test
    fun `each included row whose test passed or failed carries a ran line with its outcome`(@TempDir dir: File) {
        val id = { name: String -> "ran-lines:$name" }
        RanTests.record(id("passed"), "SUCCESSFUL")
        RanTests.record(id("failed"), "FAILED")
        RanTests.record(id("aborted"), "ABORTED")
        RanTests.record(id("left-out"), "SUCCESSFUL")
        DecisionRecord().apply {
            add(id("passed"), Verdict.REACHES_CHANGE)
            add(id("failed"), Verdict.NOT_IN_MAP)
            add(id("aborted"), Verdict.REACHES_CHANGE)
            add(id("left-out"), Verdict.SKIPPED)
            add(id("never-finished"), Verdict.REACHES_CHANGE)
        }.writeTo(dir)

        assertEquals(
            listOf("#=ran-lines:passed\tSUCCESSFUL", "#=ran-lines:failed\tFAILED"),
            read(dir).lines().filter { it.startsWith(AgentContract.RAN_LINE_PREFIX) },
        )
        assertEquals(5, rows(dir).size, "a ran line must not reach a reader of the rows")
    }

    @Test
    fun `each record carries the run token and the test JVM's identity`(@TempDir dir: File) {
        System.setProperty(AgentContract.RUN_TOKEN_PROPERTY, "token-1")
        try {
            DecisionRecord().writeTo(dir)
        } finally {
            System.clearProperty(AgentContract.RUN_TOKEN_PROPERTY)
        }

        val notes = notes(dir)
        assertEquals("token-1", notes[AgentContract.RUN_TOKEN_NOTE])
        AgentContract.JVM_IDENTITY_PROPERTIES.split(",").forEach { property ->
            assertEquals(System.getProperty(property), notes[AgentContract.JVM_NOTE_PREFIX + property], property)
        }
    }
}
