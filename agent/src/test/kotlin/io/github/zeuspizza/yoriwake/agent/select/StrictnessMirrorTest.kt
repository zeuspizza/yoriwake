package io.github.zeuspizza.yoriwake.agent.select

import io.github.zeuspizza.yoriwake.agent.contract.AgentContract
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Optimistic mode decides what the rule lines say it decides: a test runs exactly when a rule outside
 * the loosened set runs it. The selector and the rule lines are computed apart, so this is what
 * stops the mode the record describes from drifting from the mode that runs.
 */
class StrictnessMirrorTest {

    private fun id(name: String) = "[engine:junit-jupiter]/[class:$name]/[method:t()]"

    private class Fixture(
        val tests: List<Triple<String, String, String>>,
        val firstTouches: List<String>,
        val unpositioned: Set<String>,
        val notPassed: Set<String>,
        val changed: List<String>,
        val bytes: List<String>,
    )

    private fun write(dir: File, fixture: Fixture): File {
        dir.mkdirs()
        File(dir, AgentContract.COVERAGE_FILE).writeText(
            fixture.tests.joinToString("") { (test, _, classes) ->
                val outcome = if (test in fixture.notPassed) "UNKNOWN" else AgentContract.OUTCOME_SUCCESSFUL
                "$outcome\t1000\t$classes\t$test\n"
            },
        )
        File(dir, AgentContract.POSITIONS_FILE).writeText(
            fixture.tests.mapIndexedNotNull { i, (test, jvm, _) ->
                if (test in fixture.unpositioned) null else "$jvm\t${i + 1}\t$test\n"
            }.joinToString(""),
        )
        File(dir, AgentContract.FIRST_TOUCH_FILE).writeText(fixture.firstTouches.joinToString("") { "$it\n" })
        File(dir, AgentContract.NAMED_TOUCH_FILE).writeText("")
        File(dir, AgentContract.MAP_SCHEMA_VERSION_FILE).writeText("${AgentContract.MAP_SCHEMA_VERSION}\n")
        return dir
    }

    private fun decide(dir: File, fixture: Fixture, strictness: Strictness): Selector.Decision {
        val values = mapOf(
            AgentContract.SELECT_PROPERTY to "true",
            AgentContract.CHANGED_CLASSES_PROPERTY to fixture.changed.joinToString(","),
            AgentContract.CHANGED_BYTES_PROPERTY to fixture.bytes.joinToString(","),
            AgentContract.ACCOUNTED_PROPERTY to "true",
            AgentContract.MODE_PROPERTY to strictness.token(),
        )
        return FilterRule.decisionFrom({ values[it] }, dir)
    }

    private fun assertMirrors(dir: File, fixture: Fixture, label: String) {
        val safe = decide(dir, fixture, Strictness.SAFE)
        val optimistic = decide(dir, fixture, Strictness.OPTIMISTIC)
        val loosened = Strictness.OPTIMISTIC.loosened()
        if (safe.isFullRun()) {
            assertTrue(optimistic.isFullRun(), "$label: a full run stays one")
            return
        }
        val ids = fixture.tests.map { it.first } + id("NeverSeenTest")
        for (test in ids) {
            val rules = optimistic.rules()!!.rulesFor(test, Verdict.SKIPPED)
            assertEquals(
                (rules - loosened).isNotEmpty(), optimistic.includes(test),
                "$label: $test with rules $rules",
            )
            assertEquals(rules.isNotEmpty(), safe.includes(test), "$label: safe, $test with rules $rules")
        }
    }

    @Test
    fun `each window the change opens is left out, and every other rule still runs its test`(@TempDir dir: File) {
        val fixture = Fixture(
            tests = listOf(
                Triple(id("ReachesTest"), "j", "dev.Alpha"),
                Triple(id("ClassWindowTest"), "j", "dev.Other"),
                Triple(id("BytesTest"), "k", "dev.Woven"),
                Triple(id("BytesWindowTest"), "k", "dev.Other"),
                Triple(id("UnobservedTest"), "m", "dev.Other"),
                Triple(id("UnpositionedTest"), "m", "dev.Other"),
                Triple(id("FailingTest"), "m", "dev.Other"),
            ),
            firstTouches = listOf("j\t1\tdev.Alpha", "k\t3\tdev.Woven", "m\t5\t*"),
            unpositioned = setOf(id("UnpositionedTest")),
            notPassed = setOf(id("FailingTest")),
            changed = listOf("dev.Alpha"),
            bytes = listOf("dev.Woven"),
        )
        write(dir, fixture)
        val optimistic = decide(dir, fixture, Strictness.OPTIMISTIC)

        assertEquals(
            setOf(id("ReachesTest"), id("BytesTest"), id("UnobservedTest"), id("UnpositionedTest"), id("FailingTest")),
            fixture.tests.map { it.first }.filter(optimistic::includes).toSet(),
        )
        assertTrue(optimistic.includes(id("NeverSeenTest")), "a test the map never saw still runs")
        assertMirrors(dir, fixture, "fixture")
    }

    @Test
    fun `on any map and change, the optimistic selection is the tests a kept rule runs`(@TempDir root: File) {
        val random = Random(20261108)
        val classes = listOf("dev.A", "dev.B", "dev.C", "dev.D")
        repeat(300) { round ->
            val count = 2 + random.nextInt(7)
            val tests = (0 until count).map { i ->
                Triple(id("T${i}Test"), if (random.nextBoolean()) "j" else "k", classes.random(random))
            }
            val touches = buildList {
                for (jvm in listOf("j", "k")) {
                    for (name in classes + "*") {
                        if (random.nextInt(4) == 0) add("$jvm\t${1 + random.nextInt(count)}\t$name")
                    }
                }
            }
            val fixture = Fixture(
                tests = tests,
                firstTouches = touches,
                unpositioned = tests.map { it.first }.filter { random.nextInt(6) == 0 }.toSet(),
                notPassed = tests.map { it.first }.filter { random.nextInt(6) == 0 }.toSet(),
                changed = classes.filter { random.nextInt(3) == 0 },
                bytes = classes.filter { random.nextInt(5) == 0 },
            )
            val dir = write(File(root, "r$round"), fixture)
            assertMirrors(dir, fixture, "round $round")
        }
    }

    private fun windowFixture() = Fixture(
        tests = listOf(Triple(id("B1Test"), "j", "dev.AppConfig"), Triple(id("B2Test"), "j", "dev.Other")),
        firstTouches = listOf("j\t1\tdev.AppConfig"),
        unpositioned = emptySet(),
        notPassed = emptySet(),
        changed = listOf("dev.AppConfig"),
        bytes = emptyList(),
    )

    @Test
    fun `a test the safe rule runs and optimistic mode leaves out is LOOSENED, its rule line unchanged`(@TempDir dir: File) {
        val fixture = windowFixture()
        write(dir, fixture)
        val optimistic = decide(dir, fixture, Strictness.OPTIMISTIC)
        val inputs = mapOf(AgentContract.SELECT_PROPERTY to "true")::get

        assertEquals(Verdict.REACHES_CHANGE, FilterRule.verdictFor(inputs, optimistic, id("B1Test"), true))
        assertEquals(Verdict.LOOSENED, FilterRule.verdictFor(inputs, optimistic, id("B2Test"), true))
        assertEquals("shares-jvm-changed-class", optimistic.rules()!!.tokensFor(id("B2Test"), Verdict.LOOSENED))
        assertEquals("excluded", Verdict.LOOSENED.inclusionToken())
        val safe = decide(dir, fixture, Strictness.SAFE)
        assertEquals(Verdict.SHARES_JVM_WITH_CHANGE, FilterRule.verdictFor(inputs, safe, id("B2Test"), true))
    }

    @Test
    fun `a missing or unknown mode is the safe rule, and the value's letter case does not matter`() {
        assertEquals(Strictness.SAFE, Strictness.of(null))
        assertEquals(Strictness.SAFE, Strictness.of(""))
        assertEquals(Strictness.SAFE, Strictness.of("fast"))
        assertEquals(Strictness.OPTIMISTIC, Strictness.of("Optimistic"))
        assertTrue(Strictness.SAFE.loosened().isEmpty())
    }

    @Test
    fun `a class whose tests are all loosened is left out, and one with a test that runs runs whole`(@TempDir dir: File) {
        val fixture = windowFixture()
        write(dir, fixture)
        val optimistic = decide(dir, fixture, Strictness.OPTIMISTIC)
        val inputs = mapOf(AgentContract.SELECT_PROPERTY to "true")::get

        assertTrue(ClassVerdicts.of(inputs, optimistic, { false }, listOf(id("B2Test")), null).leaveOut())
        val both = ClassVerdicts.of(inputs, optimistic, { false }, listOf(id("B1Test"), id("B2Test")), null)
        assertEquals(Verdict.CLASS_GRANULARITY, both.verdicts()[id("B2Test")])
    }
}
