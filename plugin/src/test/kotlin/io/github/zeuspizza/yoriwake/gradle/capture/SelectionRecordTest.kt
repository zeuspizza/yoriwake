package io.github.zeuspizza.yoriwake.gradle.capture

import io.github.zeuspizza.yoriwake.agent.contract.AgentContract
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `selection.tsv`, the record a selecting run leaves of the tests that ran to an outcome. It exists
 * to let a later run leave those tests out, so every doubt about it removes it rather than writing it.
 */
class SelectionRecordTest {

    private val jvm = AgentContract.JVM_IDENTITY_PROPERTIES.split(",").associateWith { "value of $it" }

    private fun git(dir: File, vararg args: String): String {
        val process = ProcessBuilder("git", "-c", "user.email=t@example.com", "-c", "user.name=t", *args)
            .directory(dir).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(0, process.waitFor(), "git ${args.joinToString(" ")} failed: $output")
        return output.trim()
    }

    /** A repository with one commit, and a map directory beside it rather than inside it. */
    private fun repository(root: File): Pair<File, File> {
        val repo = File(root, "repo").apply { mkdirs() }
        git(repo, "init")
        git(repo, "commit", "--allow-empty", "-m", "base")
        return repo to File(root, "map").apply { mkdirs() }
    }

    private fun stamp(repo: File) = SelectionRecord.Stamp(
        commit = git(repo, "rev-parse", "HEAD"), clean = true, task = ":test", buildRoot = "",
        buildPath = ":", classpath = "c".repeat(64), configuration = "d".repeat(64),
    )

    private fun start(map: File, repo: File, token: String = "token-1", stamp: SelectionRecord.Stamp = stamp(repo)) =
        SelectionRecord.writeStart(map, SelectionRecord.Start(token, stamp))

    /** One test JVM's decision record, as the agent writes it. */
    private fun part(
        map: File,
        name: String,
        token: String?,
        rows: List<String>,
        ran: List<String> = rows,
        outcome: String = AgentContract.RUN_NARROWED,
        declaredRows: Int = rows.size,
        identity: Map<String, String> = jvm,
    ) {
        val lines = buildList {
            add("# test\tverdict\treason")
            add("#!${AgentContract.VERSION_NOTE}\t${AgentContract.DECISIONS_VERSION}")
            add("#!${AgentContract.ROWS_NOTE}\t$declaredRows")
            if (token != null) {
                add("#!${AgentContract.RUN_TOKEN_NOTE}\t$token")
                identity.forEach { (key, value) -> add("#!${AgentContract.JVM_NOTE_PREFIX}$key\t$value") }
            }
            add("#!${AgentContract.OUTCOME_NOTE}\t$outcome")
            rows.forEach { add("$it\tincluded\tREACHES_CHANGE") }
            ran.forEach { add("${AgentContract.RAN_LINE_PREFIX}$it\tSUCCESSFUL") }
        }
        File(map, "${AgentContract.DECISIONS_FILE}.$name${AgentContract.DECISIONS_PART_SUFFIX}")
            .writeText(lines.joinToString("\n", postfix = "\n"))
    }

    private fun record(map: File) = File(map, AgentContract.SELECTION_FILE)

    @Test
    fun `this run's parts are merged into one record of every test that ran, stamped`(@TempDir root: File) {
        val (repo, map) = repository(root)
        start(map, repo)
        part(map, "1-1", "token-1", listOf("a", "b"))
        part(map, "2-1", "token-1", listOf("c", "d"), ran = listOf("c"))

        val outcome = SelectionRecord.afterSelectingRun(map, repo, ranEverything = false)

        assertEquals(SelectionRecord.Outcome.Written(3), outcome)
        val read = SelectionRecord.read(record(map).readText())
        assertNull(read.failure())
        assertEquals(mapOf("a" to "SUCCESSFUL", "b" to "SUCCESSFUL", "c" to "SUCCESSFUL"), read.ran())
        assertEquals(stamp(repo), SelectionRecord.stampOf(read))
        assertEquals(jvm, SelectionRecord.identityOf(read))
        assertFalse(File(map, SelectionRecord.START_FILE).exists(), "the start reading outlived its decode")
    }

    @Test
    fun `a test JVM that wrote no part leaves its tests out, and the record is still written`(@TempDir root: File) {
        val (repo, map) = repository(root)
        start(map, repo)
        part(map, "1-1", "token-1", listOf("a"))

        assertEquals(SelectionRecord.Outcome.Written(1), SelectionRecord.afterSelectingRun(map, repo, false))
        assertEquals(setOf("a"), SelectionRecord.read(record(map).readText()).ran().keys)
    }

    @Test
    fun `a part another run left removes the record`(@TempDir root: File) {
        val (repo, map) = repository(root)
        record(map).writeText("an earlier run's record\n")
        start(map, repo)
        part(map, "1-1", "token-1", listOf("a"))
        part(map, "9-1", "token-0", listOf("b"))

        assertIs<SelectionRecord.Outcome.Removed>(SelectionRecord.afterSelectingRun(map, repo, false))
        assertFalse(record(map).exists())
    }

    @Test
    fun `a part with no token removes the record`(@TempDir root: File) {
        val (repo, map) = repository(root)
        start(map, repo)
        part(map, "1-1", "token-1", listOf("a"))
        part(map, "9-1", null, listOf("b"))

        assertIs<SelectionRecord.Outcome.Removed>(SelectionRecord.afterSelectingRun(map, repo, false))
        assertFalse(record(map).exists())
    }

    @Test
    fun `a part cut short removes the record`(@TempDir root: File) {
        val (repo, map) = repository(root)
        start(map, repo)
        part(map, "1-1", "token-1", listOf("a"))
        part(map, "2-1", "token-1", listOf("b"), declaredRows = 2)

        assertIs<SelectionRecord.Outcome.Removed>(SelectionRecord.afterSelectingRun(map, repo, false))
        assertFalse(record(map).exists())
    }

    @Test
    fun `parts from test JVMs on different runtimes remove the record`(@TempDir root: File) {
        val (repo, map) = repository(root)
        start(map, repo)
        part(map, "1-1", "token-1", listOf("a"))
        part(map, "2-1", "token-1", listOf("b"), identity = jvm + ("java.vendor" to "another vendor"))

        assertIs<SelectionRecord.Outcome.Removed>(SelectionRecord.afterSelectingRun(map, repo, false))
        assertFalse(record(map).exists())
    }

    @Test
    fun `HEAD moving during the run removes the record`(@TempDir root: File) {
        val (repo, map) = repository(root)
        record(map).writeText("an earlier run's record\n")
        start(map, repo)
        part(map, "1-1", "token-1", listOf("a"))
        git(repo, "commit", "--allow-empty", "-m", "during the run")

        assertIs<SelectionRecord.Outcome.Removed>(SelectionRecord.afterSelectingRun(map, repo, false))
        assertFalse(record(map).exists())
    }

    @Test
    fun `a tree that is not clean at either end removes the record`(@TempDir root: File) {
        val (repo, map) = repository(root)
        start(map, repo, stamp = stamp(repo).copy(clean = false))
        part(map, "1-1", "token-1", listOf("a"))
        assertIs<SelectionRecord.Outcome.Removed>(SelectionRecord.afterSelectingRun(map, repo, false))

        start(map, repo)
        part(map, "1-1", "token-1", listOf("a"))
        File(repo, "untracked.txt").writeText("appeared during the run")
        assertIs<SelectionRecord.Outcome.Removed>(SelectionRecord.afterSelectingRun(map, repo, false))
        assertFalse(record(map).exists())
    }

    @Test
    fun `a selecting run that did not narrow removes the record`(@TempDir root: File) {
        val (repo, map) = repository(root)
        record(map).writeText("an earlier run's record\n")
        start(map, repo)
        part(map, "1-1", "token-1", listOf("a"), outcome = AgentContract.RUN_FULL)
        assertIs<SelectionRecord.Outcome.Removed>(SelectionRecord.afterSelectingRun(map, repo, false))
        assertFalse(record(map).exists())

        record(map).writeText("an earlier run's record\n")
        start(map, repo)
        part(map, "1-1", "token-1", listOf("a"))
        assertIs<SelectionRecord.Outcome.Removed>(SelectionRecord.afterSelectingRun(map, repo, ranEverything = true))
        assertFalse(record(map).exists())

        record(map).writeText("an earlier run's record\n")
        assertIs<SelectionRecord.Outcome.Removed>(SelectionRecord.afterDeclinedRun(map))
        assertFalse(record(map).exists())
    }

    @Test
    fun `a run with no start reading removes the record`(@TempDir root: File) {
        val (repo, map) = repository(root)
        record(map).writeText("an earlier run's record\n")
        part(map, "1-1", "token-1", listOf("a"))

        assertIs<SelectionRecord.Outcome.Removed>(SelectionRecord.afterSelectingRun(map, repo, false))
        assertFalse(record(map).exists())
    }

    @Test
    fun `a record whose rows were cut short reads as unusable`(@TempDir root: File) {
        val (repo, map) = repository(root)
        start(map, repo)
        part(map, "1-1", "token-1", listOf("a", "b"))
        SelectionRecord.afterSelectingRun(map, repo, false)
        val whole = record(map).readText()

        assertNull(SelectionRecord.read(whole).failure())
        assertTrue(SelectionRecord.read(whole.lines().dropLast(2).joinToString("\n")).failure() != null)
    }

    @Test
    fun `a start reading crosses to the decode intact, and one it did not write reads as none`() {
        val start = SelectionRecord.Start(
            "token", SelectionRecord.Stamp(null, false, ":a:test", "sub/dir", ":a", "c", "d"),
        )
        assertEquals(start.stamp, SelectionRecord.Start.decode(start.encode())?.stamp)
        assertEquals("token", SelectionRecord.Start.decode(start.encode())?.token)
        listOf(null, "", "token", start.encode() + "\nextra").forEach {
            assertNull(SelectionRecord.Start.decode(it), "decoded ${it?.replace("\n", "|")}")
        }
    }
}
