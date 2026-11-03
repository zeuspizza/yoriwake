package io.github.zeuspizza.yoriwake.agent

import io.github.zeuspizza.yoriwake.agent.contract.AgentContract
import io.github.zeuspizza.yoriwake.agent.select.writeSoloOrder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The agent attached to a JVM through its premain, as a host attaches it: what premain installs
 * from the command line, and what that does to the JVM's records and to plain JUnit 4 selection.
 *
 * Each scenario runs in a JVM of its own, with no JUnit Platform on its class path. The build's
 * coverage agent, when it passes one, starts first, so a capture takes its execution data as a
 * host's capture takes JaCoCo's; those records are copied where the build's coverage reads them.
 */
class PremainCaptureTest {

    @TempDir
    lateinit var dir: File

    private var runs = 0

    /** One JVM's working directory, and where its records and loaded file go when it is told to write them. */
    private class Run(val home: File) {
        val records = File(home, "records")
        val loaded = File(home, "loaded")
        var output = ""
    }

    private fun nextRun() = Run(File(dir, "run-${runs++}").apply { mkdirs() })

    /** Runs one scenario of the premain script, the agent attached, with these system properties. */
    private fun premain(
        vararg args: String,
        run: Run = nextRun(),
        properties: List<String> = emptyList(),
        coverage: Boolean = true,
    ): Run {
        val coverageAgent = System.getProperty("yoriwake.script.coverageAgent")?.takeIf { coverage }
        val command = listOf(File(System.getProperty("java.home"), "bin/java").path) + listOfNotNull(coverageAgent) +
            listOf(
                "-javaagent:" + System.getProperty("yoriwake.script.premainAgent"),
                "-cp", System.getProperty("yoriwake.script.classpath.plain"),
                "-Dorg.gradle.test.worker=7",
            ) + properties.map { "-D$it" } +
            listOf("io.github.zeuspizza.yoriwake.capturescript.PremainScript") + args
        val process = ProcessBuilder(command).directory(run.home).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(60, TimeUnit.SECONDS)) { "the premain JVM did not exit:\n$output" }
        check(process.exitValue() == 0) { "the premain JVM failed:\n$output" }
        run.output = output
        val copyTo = System.getProperty("yoriwake.script.premainRecords")
        if (coverageAgent != null && copyTo != null) {
            for (blob in run.records.walk().filter { it.name.endsWith(".exec") }) {
                blob.copyTo(File(copyTo, "${process.pid()}-${blob.parentFile.name}-${blob.name}"))
            }
        }
        return run
    }

    private fun capturing(run: Run, root: File) = listOf(
        "${AgentContract.RECORDS_DIR_PROPERTY}=${run.records}",
        "${AgentContract.CAPTURE_ROOT_PROPERTY}=$root",
    )

    /**
     * A capturing run that reads [file] inside its one test, with or without the coverage agent. No
     * loaded file: it is written by a shutdown hook beside the one that takes the last record, and
     * its read of every loaded class lands in that record or not, as the two hooks race.
     */
    private fun reading(file: File, root: File, coverage: Boolean, vararg properties: String): Run {
        val run = nextRun()
        return premain("read", file.path, run = run, properties = capturing(run, root) + properties, coverage = coverage)
    }

    /**
     * Each record's outcome and id, the touches filed under it that say what the tests read or that
     * not everything was seen, and whether the plan completed: what the coverage agent's presence
     * must not change.
     */
    private fun observed(run: Run): List<String> {
        val worker = File(run.records, "${AgentContract.WORKER_DIR_PREFIX}7")
        val touches = File(worker, AgentContract.TOUCHES_FILE).readLines()
            .map { it.split('\t', limit = 3) }
            .filter { it.size == 3 }
            .filter {
                it[1] == AgentContract.TOUCH_RESOURCE || it[1] == AgentContract.TOUCH_ALL ||
                    it[1] == AgentContract.TOUCH_LOOKUP && it[2] == AgentContract.FIRST_TOUCH_ANY
            }
            .groupBy({ it[0] }, { "    ${it[1]} ${it[2]}" })
        val out = mutableListOf<String>()
        for (row in File(worker, AgentContract.INDEX_FILE).readLines().filter { it.isNotEmpty() }) {
            val columns = row.split('\t', limit = 5)
            out += "${columns[3]} ${columns[4]}"
            out += touches[columns[0]].orEmpty()
        }
        if (File(worker, AgentContract.PLAN_COMPLETE_FILE).isFile) out += AgentContract.PLAN_COMPLETE_FILE
        return out
    }

    private fun root(): File = File(dir, "root").apply { mkdirs() }

    @Test
    fun `a capturing JVM writes every class it loaded, sorted`() {
        val run = nextRun()
        premain("idle", run = run, properties = capturing(run, root()) + "${AgentContract.LOADED_DIR_PROPERTY}=${run.loaded}")

        val names = File(run.loaded, "${AgentContract.LOADED_WORKER_PREFIX}7${AgentContract.LOADED_WORKER_SUFFIX}")
            .readLines()
        assertEquals(names.sorted(), names)
        assertContains(names, "io.github.zeuspizza.yoriwake.capturescript.PremainScript")
        // Defined by the read hooks into java.lang, so they were installed.
        assertContains(names, "java.lang.YoriwakeReadSink")
    }

    @Test
    fun `a JVM given no loaded directory writes no loaded file`() {
        val root = root()
        val run = reading(File(root, "fixture.txt").apply { writeText("x") }, root, coverage = true)

        // It captured, and wrote no loaded file anywhere beneath its working directory.
        assertTrue(File(run.records, "${AgentContract.WORKER_DIR_PREFIX}7").isDirectory)
        val loadedFiles = run.home.walk().filter { it.name.startsWith(AgentContract.LOADED_WORKER_PREFIX) }.toList()
        assertEquals(emptyList(), loadedFiles)
    }

    @Test
    fun `every hook installs on this JDK, and a file the test reads is recorded`() {
        val root = root()
        val file = File(root, "fixture.txt").apply { writeText("x") }

        val measured = observed(reading(file, root, coverage = true))
        val bare = observed(reading(file, root, coverage = false))

        assertEquals(bare, measured)
        val test = measured.indexOf("SUCCESSFUL [engine:junit4]/[class:com.acme.ReadTest]/[method:reads]")
        assertTrue(test >= 0, measured.joinToString("\n"))
        val filedUnderTest = measured.drop(test + 1).takeWhile { it.startsWith("    ") }
        assertTrue(
            filedUnderTest.any { it == "    resource ${file.absolutePath}" || it == "    resource ${file.canonicalPath}" },
            measured.joinToString("\n"),
        )
        assertFalse(measured.any { it.startsWith("    ${AgentContract.TOUCH_ALL} ") }, measured.joinToString("\n"))
        assertFalse(measured.any { it.startsWith("    ${AgentContract.TOUCH_LOOKUP} ") }, measured.joinToString("\n"))
    }

    @Test
    fun `a default file-system provider that is not the JDK's makes every class touched`() {
        val root = root()
        val file = File(root, "fixture.txt").apply { writeText("x") }
        val provider = "java.nio.file.spi.DefaultFileSystemProvider=io.github.zeuspizza.yoriwake.capturescript.ForeignProvider"

        val measured = observed(reading(file, root, coverage = true, provider))
        val bare = observed(reading(file, root, coverage = false, provider))

        assertEquals(bare, measured)
        // Filed under the first record: from the JVM's start on, every class counts as touched.
        assertEquals(
            "    ${AgentContract.TOUCH_ALL} the default file-system provider is not the JDK's own: " +
                "io.github.zeuspizza.yoriwake.capturescript.ForeignProvider",
            measured[1],
            measured.joinToString("\n"),
        )
    }

    private val judged = "io.github.zeuspizza.yoriwake.capturescript.JudgedClasses"
    private val alpha = "$judged\$AlphaTest"
    private val beta = "$judged\$BetaTest"
    private val parameterized = "$judged\$ParameterizedAlphaTest"

    /** A map in which Alpha's test covers Alpha and Beta's covers Beta, and a run selecting against Beta's change. */
    private fun selecting(vararg extra: String): Pair<File, List<String>> {
        val map = File(dir, "map").apply { mkdirs() }
        File(map, AgentContract.COVERAGE_FILE).writeText(
            "SUCCESSFUL\t1000000\tcom.acme.Alpha\t[engine:junit4]/[class:$alpha]/[method:a]\n" +
                "SUCCESSFUL\t1000000\tcom.acme.Beta\t[engine:junit4]/[class:$beta]/[method:b]\n",
        )
        writeSoloOrder(map)
        File(map, "schema-version").writeText("${AgentContract.MAP_SCHEMA_VERSION}\n")
        return map to listOf(
            "${AgentContract.MAP_DIR_PROPERTY}=$map",
            "${AgentContract.CHANGED_CLASSES_PROPERTY}=com.acme.Beta",
            "${AgentContract.SELECT_PROPERTY}=true",
            "${AgentContract.NARROW_PLAIN_RUNNERS_PROPERTY}=${AgentContract.NARROW_PLAIN_JUNIT4}",
        ) + extra
    }

    private fun ran(run: Run) = run.output.lines().filter { it.startsWith("ran ") }.map { it.removePrefix("ran ") }.toSet()

    private fun decisions(map: File) = File(map, AgentContract.DECISIONS_FILE).readLines()

    @Test
    fun `a selecting run leaves out what misses the change and keeps the rest`() {
        val (map, properties) = selecting()

        val run = premain("execute", alpha, beta, parameterized, properties = properties)

        assertEquals(setOf(beta, parameterized), ran(run), run.output)
        val lines = decisions(map)
        assertContains(lines, "#!${AgentContract.PLAIN_FILTER_NOTE}\t${AgentContract.PLAIN_FILTER_HOOKED}")
        assertContains(lines, "#!${AgentContract.CLASS_RUNS_WHOLE_NOTE}\t${AgentContract.CLASS_RUNS_WHOLE_JUNIT4_RUNNER}=1")
        val rows = lines.filter { !it.startsWith("#") }
            .associate { it.split("\t").let { cells -> cells[0] to cells[2] } }
        assertEquals("SKIPPED", rows["[engine:junit4]/[class:$alpha]/[method:a]"], lines.joinToString("\n"))
        assertEquals("REACHES_CHANGE", rows["[engine:junit4]/[class:$beta]/[method:b]"])
        assertEquals("CLASS_RUNS_WHOLE", rows["[engine:junit4]/[class:$parameterized]/[method:p[0]]"])
        // An ignored test never runs and is never recorded, so it is not judged.
        assertFalse("[engine:junit4]/[class:$alpha]/[method:ignored]" in rows)
        assertContains(run.output, "[yoriwake] plain JUnit 4 selects whole classes: 1 left out; " +
            "1 ran whole (${AgentContract.CLASS_RUNS_WHOLE_JUNIT4_RUNNER}, first $parameterized)")
    }

    @Test
    fun `a runner that fails to describe itself runs, and the note says unsupported`() {
        val (map, properties) = selecting()
        val undescribable = "$judged\$UndescribableTest"

        val run = premain("execute", undescribable, properties = properties)

        assertEquals(setOf(undescribable), ran(run), run.output)
        assertContains(run.output, "[yoriwake] plain JUnit 4 selection failed, so the class runs: ")
        assertContains(decisions(map), "#!${AgentContract.PLAIN_FILTER_NOTE}\t${AgentContract.PLAIN_FILTER_UNSUPPORTED}")
    }

    @Test
    fun `an observing run leaves nothing out but records what it would have`() {
        val (map, properties) = selecting("${AgentContract.OBSERVE_PROPERTY}=true")

        val run = premain("execute", alpha, beta, properties = properties)

        assertEquals(setOf(alpha, beta), ran(run), run.output)
        val lines = decisions(map)
        assertContains(lines, "#!${AgentContract.PLAIN_FILTER_NOTE}\t${AgentContract.PLAIN_FILTER_HOOKED}")
        assertTrue(
            lines.any {
                it.startsWith("${AgentContract.OBSERVATION_LINE_PREFIX}[engine:junit4]/[class:$alpha]/[method:a]\t") &&
                    it.endsWith("\tSKIPPED")
            },
            lines.joinToString("\n"),
        )
        assertFalse("selects whole classes" in run.output, run.output)
    }

    @Test
    fun `with no executor rewritten the note says unhooked`() {
        val (map, properties) = selecting()

        premain("idle", properties = properties)

        assertContains(decisions(map), "#!${AgentContract.PLAIN_FILTER_NOTE}\t${AgentContract.PLAIN_FILTER_UNHOOKED}")
    }
}
