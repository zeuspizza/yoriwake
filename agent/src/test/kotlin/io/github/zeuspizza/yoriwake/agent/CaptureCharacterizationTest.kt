package io.github.zeuspizza.yoriwake.agent

import io.github.zeuspizza.yoriwake.agent.contract.AgentContract
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals

/**
 * The exact records each engine writes for a scripted event sequence, pinned before the capture
 * engines were merged so any change to them is one somebody chose.
 *
 * Each scenario runs in a JVM of its own, as a host's test JVM does: the JUnit 4 and TestNG paths
 * flush only on a real shutdown, and run with no JUnit Platform on the classpath. A stand-in JaCoCo
 * runtime returns, as each window's bytes, the names of the work that ran in it.
 *
 * A record renders as `outcome id <- work`, the work that ran in its window; a test record with
 * no timing shows its duration.
 */
class CaptureCharacterizationTest {

    @TempDir
    lateinit var dir: File

    private enum class Engine(val main: String, val classpath: String) {
        PLATFORM("PlatformScript", "yoriwake.script.classpath.platform"),
        JUNIT4("JUnit4Script", "yoriwake.script.classpath.plain"),
        TESTNG("TestNgScript", "yoriwake.script.classpath.plain"),
    }

    /** Runs one scenario and renders every record and marker it left behind. */
    private fun capture(engine: Engine, scenario: String, vararg properties: String): String {
        val raw = File(dir, "raw")
        val loaded = File(dir, "loaded")
        val command = listOf(
            File(System.getProperty("java.home"), "bin/java").path,
            "-cp", System.getProperty(engine.classpath),
            "-D${AgentContract.RECORDS_DIR_PROPERTY}=$raw",
            "-D${AgentContract.LOADED_DIR_PROPERTY}=$loaded",
        ) + properties.map { "-D$it" } + listOf("io.github.zeuspizza.yoriwake.capturescript.${engine.main}", scenario)
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(60, TimeUnit.SECONDS)) { "the scripted JVM did not exit:\n$output" }
        check(process.exitValue() == 0) { "the scripted JVM failed:\n$output" }
        return render(raw, loaded, process.pid().toString())
    }

    private fun render(raw: File, loaded: File, pid: String): String {
        val out = mutableListOf<String>()
        for (worker in raw.listFiles().orEmpty().sortedBy { it.name }) {
            val complete = File(worker, AgentContract.PLAN_COMPLETE_FILE).isFile
            out += worker.name.replace(pid, "<pid>") + if (complete) " plan-complete" else ""
            val index = File(worker, AgentContract.INDEX_FILE)
            for (row in index.readLines().filter { it.isNotEmpty() }) {
                val columns = row.split('\t', limit = 5)
                if (columns.size < 5) {
                    out += "  malformed row: $row"
                    continue
                }
                val (sequence, duration, _, outcome, id) = columns
                val blob = File(worker, AgentContract.EXEC_FILE_FORMAT.format(sequence.toInt()))
                val work = if (blob.isFile) blob.readText().removePrefix("#").ifEmpty { "nothing" } else "<no blob>"
                val timing = when {
                    duration.toLong() < 0 -> " duration=$duration"
                    duration.toLong() == 0L && outcome != AgentContract.OUTCOME_NOT_A_TEST &&
                        outcome != AgentContract.OUTCOME_SKIPPED -> " duration=0"
                    else -> ""
                }
                out += "  $outcome $id <- $work$timing"
            }
        }
        for (marker in loaded.listFiles().orEmpty().map { it.name }.sorted()) {
            out += marker.replace(pid, "<pid>")
        }
        return out.joinToString("\n")
    }

    @Test
    fun `platform - tests across classes, a nested class, every outcome and an id with no class`() {
        assertEquals(
            """
            worker-<pid> plan-complete
              NONE [yoriwake:unattributed] <- jvm-startup
              NONE [yoriwake:class][engine:junit-jupiter]/[class:com.acme.ATest] <- A-beforeAll
              SUCCESSFUL [engine:junit-jupiter]/[class:com.acme.ATest]/[method:a1()] <- a1
              NONE [yoriwake:class][engine:junit-jupiter]/[class:com.acme.ATest] <- A-afterEach
              FAILED [engine:junit-jupiter]/[class:com.acme.ATest]/[nested-class:Inner]/[method:i1()] <- i1
              NONE [yoriwake:class][engine:junit-jupiter]/[class:com.acme.ATest]|[engine:junit-jupiter]/[class:com.acme.BTest] <- A-afterAll,B-beforeAll
              ABORTED [engine:junit-jupiter]/[class:com.acme.BTest]/[method:b1()] <- b1
              SKIPPED [engine:junit-jupiter]/[class:com.acme.BTest]/[method:b2()] <- nothing
              NONE [yoriwake:unattributed] <- B-afterAll
              SUCCESSFUL [engine:junit-vintage]/[runner:com.acme.OldTest]/[test:t(com.acme.OldTest)] <- old
              NONE [yoriwake:class][engine:junit-jupiter]/[class:com.acme.CTest] <- after-old
              UNKNOWN [engine:junit-jupiter]/[class:com.acme.CTest]/[method:c1()] <- c1
              NONE [yoriwake:class][engine:junit-jupiter]/[class:com.acme.CTest] <- C-afterAll
            loaded-<pid>.complete
            """.trimIndent(),
            capture(Engine.PLATFORM, "sequence"),
        )
    }

    @Test
    fun `platform - a test starting on another thread while one is open`() {
        assertEquals(
            """
            worker-7 plan-complete
              NONE [yoriwake:unattributed] <- nothing
              NONE [yoriwake:class][engine:junit-jupiter]/[class:com.acme.ATest] <- A-beforeAll
              UNKNOWN [engine:junit-jupiter]/[class:com.acme.ATest]/[method:a1()] <- a1-early,b1,a1-late
              UNKNOWN [engine:junit-jupiter]/[class:com.acme.BTest]/[method:b1()] <- nothing
              NONE [yoriwake:class][engine:junit-jupiter]/[class:com.acme.BTest] <- tail
            loaded-7.complete
            """.trimIndent(),
            capture(Engine.PLATFORM, "overlap-threads", "org.gradle.test.worker=7"),
        )
    }

    @Test
    fun `platform - a test starting on the same thread inside one that is not its parent`() {
        assertEquals(
            """
            worker-7 plan-complete
              NONE [yoriwake:unattributed] <- nothing
              NONE [yoriwake:class][engine:junit-jupiter]/[class:com.acme.ATest] <- nothing
              UNKNOWN [engine:junit-jupiter]/[class:com.acme.BTest]/[method:b1()] <- a1-early,b1
              UNKNOWN [engine:junit-jupiter]/[class:com.acme.ATest]/[method:a1()] <- a1-late
              NONE [yoriwake:class][engine:junit-jupiter]/[class:com.acme.BTest] <- tail
            loaded-7.complete
            """.trimIndent(),
            capture(Engine.PLATFORM, "overlap-one-thread", "org.gradle.test.worker=7"),
        )
    }

    @Test
    fun `platform - a test that reports tests of its own`() {
        assertEquals(
            """
            worker-7 plan-complete
              NONE [yoriwake:unattributed] <- nothing
              NONE [yoriwake:unattributed] <- nothing
              NONE [yoriwake:unattributed] <- where-block
              SUCCESSFUL [engine:spock]/[spec:com.acme.HandlerSpec]/[feature:${'$'}spock_feature_0_0]/[iteration:0] <- it0
              NONE [yoriwake:unattributed] <- between-iterations
              SUCCESSFUL [engine:spock]/[spec:com.acme.HandlerSpec]/[feature:${'$'}spock_feature_0_0]/[iteration:1] <- it1
              SUCCESSFUL [engine:spock]/[spec:com.acme.HandlerSpec]/[feature:${'$'}spock_feature_0_0] <- feature-cleanup
              NONE [yoriwake:class][engine:junit-jupiter]/[class:com.acme.DTest] <- nothing
              NONE [yoriwake:class][engine:junit-jupiter]/[class:com.acme.DTest] <- d1-setup
              SUCCESSFUL [engine:junit-jupiter]/[class:com.acme.DTest]/[method:d1()]/[dynamic-test:#1] <- d1-1
              SUCCESSFUL [engine:junit-jupiter]/[class:com.acme.DTest]/[method:d1()] <- d1-cleanup
              NONE [yoriwake:class][engine:junit-jupiter]/[class:com.acme.DTest] <- tail
            loaded-7.complete
            """.trimIndent(),
            capture(Engine.PLATFORM, "children", "org.gradle.test.worker=7"),
        )
    }

    @Test
    fun `platform - a nested Launcher's listener leaves the outer test's window alone`() {
        assertEquals(
            """
            worker-7 plan-complete
              NONE [yoriwake:unattributed] <- nothing
              NONE [yoriwake:class][engine:junit-jupiter]/[class:com.acme.ATest] <- nothing
              SUCCESSFUL [engine:junit-jupiter]/[class:com.acme.ATest]/[method:a1()] <- a1-before-nested,x1,a1-after-nested
              NONE [yoriwake:class][engine:junit-jupiter]/[class:com.acme.ATest] <- tail
            loaded-7.complete
            """.trimIndent(),
            capture(Engine.PLATFORM, "nested-launcher", "org.gradle.test.worker=7"),
        )
    }

    @Test
    fun `platform - nothing is captured while another capture holds the claim`() {
        assertEquals(
            "",
            capture(Engine.PLATFORM, "one-test", "org.gradle.test.worker=7", "$CLAIM=elsewhere"),
        )
    }

    @Test
    fun `platform - TestNG run by the Platform, seen by both listeners`() {
        assertEquals(
            """
            worker-7 plan-complete
              NONE [yoriwake:unattributed] <- nothing
              NONE [yoriwake:class][engine:testng]/[class:com.acme.NgTest] <- setup
              SUCCESSFUL [engine:testng]/[class:com.acme.NgTest]/[method:m1()] <- m1
              NONE [yoriwake:class][engine:testng]/[class:com.acme.NgTest] <- after-m1
              SUCCESSFUL [engine:testng]/[class:com.acme.NgTest]/[method:m2()] <- m2
              NONE [yoriwake:class][engine:testng]/[class:com.acme.NgTest] <- after-m2
            loaded-7.complete
            """.trimIndent(),
            capture(Engine.PLATFORM, "testng-on-platform", "org.gradle.test.worker=7"),
        )
    }

    @Test
    fun `junit4 - tests across classes, a failure, an assumption failure and a bracketed class`() {
        assertEquals(
            """
            worker-<pid> plan-complete
              NONE [yoriwake:unattributed] <- jvm-startup,A-beforeClass
              SUCCESSFUL [engine:junit4]/[class:com.acme.ATest]/[method:a1] <- a1
              NONE [yoriwake:class][engine:junit4]/[class:com.acme.ATest] <- A-after
              FAILED [engine:junit4]/[class:com.acme.ATest]/[method:a2] <- a2
              NONE [yoriwake:class][engine:junit4]/[class:com.acme.ATest]|[engine:junit4]/[class:com.acme.BTest] <- A-afterClass,B-beforeClass
              FAILED [engine:junit4]/[class:com.acme.BTest]/[method:b1] <- b1
              NONE [yoriwake:class][engine:junit4]/[class:com.acme.BTest]|[engine:junit4]/[class:com.acme.Weird[Name]Test] <- B-afterClass,W-beforeClass
              SUCCESSFUL [engine:junit4]/[class:com.acme.Weird[Name]Test]/[method:w1] <- w1
              NONE [yoriwake:class][engine:junit4]/[class:com.acme.Weird[Name]Test] <- W-after
              SUCCESSFUL [engine:junit4]/[class:com.acme.Weird[Name]Test]/[method:w2] <- w2
              NONE [yoriwake:class][engine:junit4]/[class:com.acme.Weird[Name]Test]|[engine:junit4]/[class:com.acme.CTest] <- W-afterClass,C-beforeClass
              SUCCESSFUL [engine:junit4]/[class:com.acme.CTest]/[method:c1] <- c1
              NONE [yoriwake:unattributed] <- tail
            loaded-<pid>.complete
            """.trimIndent(),
            capture(Engine.JUNIT4, "sequence"),
        )
    }

    @Test
    fun `junit4 - a test that runs other tests inside itself`() {
        assertEquals(
            """
            worker-7 plan-complete
              NONE [yoriwake:unattributed] <- startup
              SUCCESSFUL [engine:junit4]/[class:com.acme.ATest]/[method:outer] <- outer-before,n1,between-inner,n2,outer-after
              NONE [yoriwake:class][engine:junit4]/[class:com.acme.ATest]|[engine:junit4]/[class:com.acme.BTest] <- between
              SUCCESSFUL [engine:junit4]/[class:com.acme.BTest]/[method:b1] <- b1
              NONE [yoriwake:unattributed] <- tail
            loaded-7.complete
            """.trimIndent(),
            capture(Engine.JUNIT4, "nested-run", "org.gradle.test.worker=7"),
        )
    }

    @Test
    fun `junit4 - a test starting on another thread while one is open`() {
        assertEquals(
            """
            worker-7 plan-complete
              NONE [yoriwake:unattributed] <- startup
              UNKNOWN [engine:junit4]/[class:com.acme.ATest]/[method:a1] <- a1-early,b1,a1-late
              UNKNOWN [engine:junit4]/[class:com.acme.BTest]/[method:b1] <- nothing
              NONE [yoriwake:class][engine:junit4]/[class:com.acme.BTest]|[engine:junit4]/[class:com.acme.CTest] <- between
              SUCCESSFUL [engine:junit4]/[class:com.acme.CTest]/[method:c1] <- c1
              NONE [yoriwake:unattributed] <- tail
            loaded-7.complete
            """.trimIndent(),
            capture(Engine.JUNIT4, "overlap-threads", "org.gradle.test.worker=7"),
        )
    }

    @Test
    fun `junit4 - a run the runner never said finished is not complete`() {
        assertEquals(
            """
            worker-7
              NONE [yoriwake:unattributed] <- nothing
              SUCCESSFUL [engine:junit4]/[class:com.acme.ATest]/[method:a1] <- a1
              NONE [yoriwake:unattributed] <- nothing
            """.trimIndent(),
            capture(Engine.JUNIT4, "unfinished", "org.gradle.test.worker=7"),
        )
    }

    @Test
    fun `junit4 - nothing is captured while another capture holds the claim`() {
        assertEquals(
            "",
            capture(Engine.JUNIT4, "one-test", "org.gradle.test.worker=7", "$CLAIM=elsewhere"),
        )
    }

    @Test
    fun `testng - tests across classes and every way a test closes`() {
        assertEquals(
            """
            worker-<pid> plan-complete
              NONE [yoriwake:unattributed] <- jvm-startup,A-beforeClass
              SUCCESSFUL [engine:testng]/[class:com.acme.ATest]/[method:a1] <- a1
              NONE [yoriwake:class][engine:testng]/[class:com.acme.ATest] <- A-afterMethod
              FAILED [engine:testng]/[class:com.acme.ATest]/[method:a2] <- a2
              NONE [yoriwake:class][engine:testng]/[class:com.acme.ATest] <- nothing
              FAILED [engine:testng]/[class:com.acme.ATest]/[method:a3] <- a3
              NONE [yoriwake:class][engine:testng]/[class:com.acme.ATest] <- nothing
              FAILED [engine:testng]/[class:com.acme.ATest]/[method:a4] <- a4
              NONE [yoriwake:class][engine:testng]/[class:com.acme.ATest]|[engine:testng]/[class:com.acme.BTest] <- A-afterClass,B-beforeClass
              SUCCESSFUL [engine:testng]/[class:com.acme.BTest]/[method:b1] <- b1
              NONE [yoriwake:unattributed] <- tail
            loaded-<pid>.complete
            """.trimIndent(),
            capture(Engine.TESTNG, "sequence"),
        )
    }

    @Test
    fun `testng - a test starting on another thread while one is open`() {
        assertEquals(
            """
            worker-7 plan-complete
              NONE [yoriwake:unattributed] <- startup
              UNKNOWN [engine:testng]/[class:com.acme.ATest]/[method:a1] <- a1-early,b1,a1-late
              UNKNOWN [engine:testng]/[class:com.acme.BTest]/[method:b1] <- nothing
              NONE [yoriwake:unattributed] <- tail
            loaded-7.complete
            """.trimIndent(),
            capture(Engine.TESTNG, "overlap-threads", "org.gradle.test.worker=7"),
        )
    }

    private companion object {
        /** The JVM-wide capture claim, spelled out: the scripted JVM is where it is read. */
        const val CLAIM = "yoriwake.internal.capture.owner"
    }
}
