package io.github.zeuspizza.yoriwake.agent.select

import io.github.zeuspizza.yoriwake.agent.contract.AgentContract
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A class that changed in line information only: it forces nothing, and runs the tests at or after
 * their JVM's first debug touch of it, never one a changed class would not run.
 */
class LinesOnlySelectorTest {

    @TempDir
    lateinit var dir: File

    private fun id(name: String) = "[engine:junit-jupiter]/[class:com.acme.$name]/[method:t()]"

    /**
     * A map of one JVM, `jvm`, whose tests ran in [tests]' order, each covering its classes.
     * [debug] are `sequence to class` rows of the debug-touch table; null writes none.
     */
    private fun map(
        tests: List<Pair<String, String>>,
        firstTouches: List<Pair<Int, String>> = emptyList(),
        debug: List<Pair<Int, String>>? = emptyList(),
        recorded: Boolean = true,
        startup: String? = null,
        unpositioned: List<String> = emptyList(),
    ): MapReader.Result {
        val rows = tests.map { (name, classes) -> "SUCCESSFUL\t1\t$classes\t${id(name)}" } +
            unpositioned.map { "SUCCESSFUL\t1\tcom.acme.Other\t${id(it)}" } +
            listOfNotNull(startup?.let { "NONE\t0\t$it\t${AgentContract.UNATTRIBUTED_RECORD_ID}" })
        File(dir, AgentContract.COVERAGE_FILE).writeText(rows.joinToString("") { "$it\n" })
        File(dir, AgentContract.POSITIONS_FILE)
            .writeText(tests.mapIndexed { i, (name, _) -> "jvm\t${i + 1}\t${id(name)}\n" }.joinToString(""))
        File(dir, AgentContract.FIRST_TOUCH_FILE)
            .writeText(firstTouches.joinToString("") { (sequence, name) -> "jvm\t$sequence\t$name\n" })
        File(dir, AgentContract.NAMED_TOUCH_FILE).writeText("")
        val debugFile = File(dir, AgentContract.DEBUG_TOUCH_FILE)
        if (debug == null) debugFile.delete() else debugFile.writeText(
            "${AgentContract.DEBUG_TOUCH_STAMP}\tabc\tdef\n" +
                (if (recorded) "jvm\t0\t${AgentContract.DEBUG_RECORDED}\n" else "") +
                debug.joinToString("") { (sequence, name) -> "jvm\t$sequence\t$name\n" }
        )
        File(dir, AgentContract.MAP_SCHEMA_VERSION_FILE).writeText("${AgentContract.MAP_SCHEMA_VERSION}\n")
        return MapReader.read(dir)
    }

    private fun lines(vararg prefixes: String, changed: List<String> = emptyList()) =
        ChangeSet.of(changed, emptyList()).withAccountedFor(changed.isEmpty()).withLinesOnly(prefixes.toList())

    /** Of the tests the map holds, the ones [decision] runs are exactly [names]. */
    private fun ran(decision: Selector.Decision, vararg names: String) = assertEquals(
        names.map(::id).toSet(),
        listOf("A", "B", "C", "D").map(::id)
            .filter { decision.reasonFor(it) != Selector.Decision.Reason.NOT_IN_MAP && decision.includes(it) }.toSet(),
    )

    @Test
    fun `a covering test before any debug touch does not run, and nothing forces`() {
        val map = map(listOf("A" to "com.acme.Beta", "B" to "com.acme.Other"), firstTouches = listOf(1 to "com.acme.Beta"))
        val decision = Selector.decide(map, lines("com.acme.Beta"), emptyList())

        assertFalse(decision.isFullRun, decision.fullRunReason())
        ran(decision)
    }

    @Test
    fun `a line-only class no test recorded forces nothing`() {
        val map = map(listOf("A" to "com.acme.Other"))
        val decision = Selector.decide(map, lines("com.acme.Unseen"), emptyList())

        assertFalse(decision.isFullRun, decision.fullRunReason())
    }

    @Test
    fun `a line-only class in startup coverage forces nothing`() {
        val map = map(listOf("A" to "com.acme.Other"), startup = "com.acme.Beta")
        assertFalse(Selector.decide(map, lines("com.acme.Beta"), emptyList()).isFullRun)
    }

    @Test
    fun `an emptied change set is accounted for`() {
        val map = map(listOf("A" to "com.acme.Beta"))
        val decision = Selector.decide(map, lines("com.acme.Beta"), emptyList())

        assertFalse(decision.isFullRun, decision.fullRunReason())
    }

    @Test
    fun `the test at the first debug touch and every later one of its JVM run`() {
        val map = map(
            listOf("A" to "com.acme.Beta", "B" to "com.acme.Beta", "C" to "com.acme.Other"),
            firstTouches = listOf(1 to "com.acme.Beta"),
            debug = listOf(2 to "com.acme.Beta"),
        )
        ran(Selector.decide(map, lines("com.acme.Beta"), emptyList()), "B", "C")
    }

    @Test
    fun `a nested class's frame opens the window of its source file`() {
        val map = map(
            listOf("A" to "com.acme.Beta", "B" to "com.acme.Other"),
            firstTouches = listOf(1 to "com.acme.Beta"),
            debug = listOf(2 to "com.acme.Beta\$Inner"),
        )
        ran(Selector.decide(map, lines("com.acme.Beta"), emptyList()), "B")
    }

    @Test
    fun `a debug touch opens no window before the class arrived in that JVM`() {
        // An element built by hand for a class the JVM never loaded shows none of its lines.
        val map = map(
            listOf("A" to "com.acme.Other", "B" to "com.acme.Other", "C" to "com.acme.Beta"),
            firstTouches = listOf(3 to "com.acme.Beta"),
            debug = listOf(1 to "com.acme.Beta"),
        )
        ran(Selector.decide(map, lines("com.acme.Beta"), emptyList()), "C")
    }

    @Test
    fun `a debug touch of every class opens the window from there on`() {
        val map = map(
            listOf("A" to "com.acme.Beta", "B" to "com.acme.Other", "C" to "com.acme.Other"),
            firstTouches = listOf(1 to "com.acme.Beta"),
            debug = listOf(2 to AgentContract.FIRST_TOUCH_ANY),
        )
        ran(Selector.decide(map, lines("com.acme.Beta"), emptyList()), "B", "C")
    }

    @Test
    fun `a JVM whose stack traces were not recorded runs what a changed class would`() {
        val map = map(
            listOf("A" to "com.acme.Other", "B" to "com.acme.Beta", "C" to "com.acme.Other"),
            firstTouches = listOf(2 to "com.acme.Beta"),
            recorded = false,
        )
        ran(Selector.decide(map, lines("com.acme.Beta"), emptyList()), "B", "C")
    }

    @Test
    fun `a map without debug touches judges the class as changed`() {
        val map = map(listOf("A" to "com.acme.Beta", "B" to "com.acme.Other"), debug = null)
        ran(Selector.decide(map, lines("com.acme.Beta"), emptyList()), "A")
    }

    @Test
    fun `a test the map holds no position for runs`() {
        val map = map(listOf("A" to "com.acme.Beta"), firstTouches = listOf(1 to "com.acme.Beta"), unpositioned = listOf("D"))
        ran(Selector.decide(map, lines("com.acme.Beta"), emptyList()), "D")
    }

    @Test
    fun `the rule lines name the line window`() {
        val map = map(
            listOf("A" to "com.acme.Beta", "B" to "com.acme.Beta"),
            firstTouches = listOf(1 to "com.acme.Beta"),
            debug = listOf(2 to "com.acme.Beta"),
        )
        val rules = Selector.rules(map, lines("com.acme.Beta"), true)

        assertEquals(setOf(Rule.SHARES_JVM_LINES_OBSERVED), rules.rulesFor(id("B"), Verdict.SHARES_JVM_WITH_CHANGE))
        assertTrue(rules.rulesFor(id("A"), Verdict.SKIPPED).isEmpty(), rules.rulesFor(id("A"), Verdict.SKIPPED).toString())
    }

    /**
     * Never wider: for random maps and touches, a class judged line-only selects a subset of what the
     * same class selects when changed, and never runs everything where that narrows.
     */
    @Test
    fun `line-only selection is never wider than the changed class's`() {
        val random = Random(80)
        val classes = listOf("com.acme.Beta", "com.acme.Beta\$Inner", "com.acme.Other", AgentContract.FIRST_TOUCH_ANY)
        repeat(300) { round ->
            val names = listOf("A", "B", "C", "D")
            val tests = names.map { it to classes.take(3).filter { random.nextBoolean() }.ifEmpty { listOf("com.acme.Other") }.joinToString(",") }
            val touches = { classes.filter { random.nextInt(3) == 0 }.map { random.nextInt(0, 6) to it } }
            val map = map(
                tests,
                firstTouches = touches(),
                debug = touches(),
                recorded = random.nextBoolean(),
                startup = if (random.nextInt(4) == 0) "com.acme.Beta" else null,
            )
            val off = Selector.decide(map, ChangeSet.of(listOf("com.acme.Beta"), emptyList()), emptyList())
            val on = Selector.decide(map, lines("com.acme.Beta"), emptyList())
            val all = names.map(::id)
            assertFalse(on.isFullRun && !off.isFullRun, "round $round: flag-on ran everything where flag-off narrowed")
            if (!off.isFullRun) {
                val wider = all.filter { on.includes(it) && !off.includes(it) }
                assertTrue(wider.isEmpty(), "round $round: flag-on also ran $wider")
            }
        }
    }
}
