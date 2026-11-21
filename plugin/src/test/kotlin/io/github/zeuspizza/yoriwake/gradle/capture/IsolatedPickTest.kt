package io.github.zeuspizza.yoriwake.gradle.capture

import io.github.zeuspizza.yoriwake.agent.contract.AgentContract
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IsolatedPickTest {

    private fun id(testClass: String, method: String) =
        "[engine:junit-jupiter]/[class:dev.sample.$testClass]/[method:$method()]"

    /**
     * One shared JVM running ATest, BTest, then CTest's two tests, each class touching one class
     * of its own first; CTest's second test covers nothing in scope. A second JVM, isolated, runs
     * DTest alone. ATest and BTest take a second each, CTest two, DTest one.
     */
    private fun map(dir: File, cTestAlone: Boolean = false, cJvmLabel: String = "isolated", cNanos: Long = 1000000000): File {
        val shared = "run/worker-1"
        val alone = "run/worker-2"
        val cJvm = if (cTestAlone) "run/worker-3" else shared
        File(dir, AgentContract.MAP_SCHEMA_VERSION_FILE).writeText("${AgentContract.MAP_SCHEMA_VERSION}\n")
        File(dir, AgentContract.SCOPE_FILE).writeText("dev.sample\n")
        File(dir, AgentContract.COVERAGE_FILE).writeText(
            listOf(
                "SUCCESSFUL\t1000000000\tdev.sample.Alpha\t${id("ATest", "a")}",
                "SUCCESSFUL\t1000000000\tdev.sample.Beta\t${id("BTest", "b")}",
                "SUCCESSFUL\t$cNanos\tdev.sample.Other\t${id("CTest", "c1")}",
                "SUCCESSFUL\t$cNanos\t\t${id("CTest", "c2")}",
                "SUCCESSFUL\t1000000000\tdev.sample.Delta\t${id("DTest", "d")}",
            ).joinToString("\n", postfix = "\n"),
        )
        File(dir, AgentContract.POSITIONS_FILE).writeText(
            listOf(
                "$shared\t1\t${id("ATest", "a")}",
                "$shared\t2\t${id("BTest", "b")}",
                "$cJvm\t3\t${id("CTest", "c1")}",
                "$cJvm\t4\t${id("CTest", "c2")}",
                "$alone\t1\t${id("DTest", "d")}",
            ).joinToString("\n", postfix = "\n"),
        )
        File(dir, AgentContract.FIRST_TOUCH_FILE).writeText(
            listOf(
                "$shared\t1\tdev.sample.Alpha",
                "$shared\t1\torg.junit.Outside",
                "$shared\t2\tdev.sample.Beta",
                "$cJvm\t3\tdev.sample.Other",
                "$alone\t1\tdev.sample.Delta",
            ).joinToString("\n", postfix = "\n"),
        )
        File(dir, AgentContract.JVM_MODE_FILE).writeText(
            listOfNotNull("$shared\tshared", "$alone\tisolated", "$cJvm\t$cJvmLabel".takeIf { cTestAlone })
                .joinToString("\n", postfix = "\n"),
        )
        return dir
    }

    private fun pick(
        ranking: IsolatedPick.Ranking,
        budget: Double,
        forks: Int = 1,
        previous: IsolatedPick.Stamp? = null,
    ) = IsolatedPick.pick(ranking, previous, budget, hostForks = 1, isolatedForks = forks, processors = 8) { true }

    @Test
    fun `a class scores what its shared JVM touched first at or before each of its tests, less its own coverage`(
        @TempDir dir: File,
    ) {
        val ranking = IsolatedPick.rank(map(dir))!!

        // CTest: 3 - 1, then 3 - 0. BTest: 2 - 1. ATest: 1 - 1. DTest ran alone, so it has no score.
        assertEquals(mapOf("dev.sample.ATest" to 0L, "dev.sample.BTest" to 1L, "dev.sample.CTest" to 5L), ranking.scores)
        assertEquals(setOf("dev.sample.DTest"), ranking.isolated)
        assertEquals(2.0, ranking.seconds["dev.sample.CTest"])
    }

    @Test
    fun `no map ranks nothing`(@TempDir dir: File) {
        assertNull(IsolatedPick.rank(dir))
    }

    @Test
    fun `the pick stops at the budget`(@TempDir dir: File) {
        val ranking = IsolatedPick.rank(map(dir))!!

        // Shared: 5 s on one JVM, so 2.2x allows 6 s more. CTest costs a JVM start and twice its 2 s; BTest
        // 2 s more, which fits only spread over four JVMs.
        assertEquals(listOf("dev.sample.CTest"), pick(ranking, 2.2).classes.keys.toList())
        assertEquals(listOf("dev.sample.CTest", "dev.sample.BTest"), pick(ranking, 2.2, forks = 4).classes.keys.toList())
        // A class that ranks 0 gains nothing alone, however much the budget allows.
        assertEquals(listOf("dev.sample.CTest", "dev.sample.BTest"), pick(ranking, 10.0).classes.keys.toList())
    }

    /** [map] with its JVMs measured: the shared one took [sharedJvm] s, and CTest's own, when alone, [cJvm] s. */
    private fun measuredMap(dir: File, sharedJvm: Double, cJvm: Double? = null): File {
        map(dir, cTestAlone = cJvm != null)
        File(dir, CoverageDecoder.JVM_SECONDS_FILE).writeText(
            listOfNotNull("run/worker-1\t$sharedJvm", "run/worker-2\t3.0", cJvm?.let { "run/worker-3\t$it" })
                .joinToString("\n", postfix = "\n"),
        )
        return dir
    }

    @Test
    fun `a class costs a shared JVM's measured start and twice its tests, within the budget`(@TempDir dir: File) {
        // The shared JVM took 20 s for 4 s of tests: a 16 s start overhead; the baseline is 20 s.
        val ranking = IsolatedPick.rank(measuredMap(dir, sharedJvm = 20.0))!!
        assertEquals(16.0, ranking.startOverhead)
        assertEquals(20.0, ranking.sharedJvmSeconds)
        assertEquals(mapOf("dev.sample.DTest" to 3.0), ranking.isolatedSeconds)

        for (budget in listOf(1.5, 2.0, 3.0, 5.0)) {
            for (forks in listOf(1, 4)) {
                val pick = pick(ranking, budget, forks)
                val spent = pick.classSeconds.values.sum()
                assertTrue(spent / forks <= (budget - 1) * 20.0, "$budget x, $forks forks: $spent s")
            }
        }
        // CTest costs 16 + 2 x 2 s: over 1.5x's 10 s, within 2x's 20 s.
        assertEquals(emptyMap(), pick(ranking, 1.5).classes)
        assertEquals(mapOf("dev.sample.CTest" to 20.0), pick(ranking, 2.0).classSeconds)
    }

    @Test
    fun `a class the map measured alone costs what its own JVM took`(@TempDir dir: File) {
        val first = pick(IsolatedPick.rank(measuredMap(dir, sharedJvm = 20.0))!!, 2.0)
        val next = IsolatedPick.rank(measuredMap(File(dir, "next").apply { mkdirs() }, sharedJvm = 12.0, cJvm = 30.0))!!

        // Picked from scratch (a stamp that predates the sticky pick): alone it took 30 s, more than
        // the 20 s the budget allows over the 20 s it was picked against.
        val older = first.copy(candidateClasses = null)
        assertEquals(emptyMap(), pick(next, 2.0, previous = older).classes)
        assertEquals(mapOf("dev.sample.CTest" to 5L), pick(next, 2.6, previous = older).classes)
    }

    private fun ranking(vararg seconds: Double, isolatedSeconds: Map<String, Double> = emptyMap()) = IsolatedPick.Ranking(
        scores = seconds.indices.associate { "dev.sample.T$it" to (if (it < seconds.size - 1) 100L - it else 0L) },
        isolated = isolatedSeconds.keys,
        seconds = seconds.indices.associate { "dev.sample.T$it" to seconds[it] },
        isolatedSeconds = isolatedSeconds,
        startOverhead = 1.0,
        sharedJvmSeconds = 10.0,
    )

    @Test
    fun `fewer classes than JVMs are predicted at the slowest of them`() {
        // Estimated at 3, 5 and 7 seconds; the fourth class scores 0 and stays shared.
        val pick = pick(ranking(1.0, 2.0, 3.0, 1.0), 10.0, forks = 4)
        assertEquals(3, pick.classes.size)
        assertEquals(7.0, pick.predictedExtra)
    }

    /**
     * Budgeted recordings in a row, on 4 JVMs, of 40 classes estimated at 3 s each whose own JVMs
     * report 2 s and each take 5 s of an isolated JVM's wall time. The shared recording takes 20 s,
     * 4 s of it outside its JVMs. Returns each pick's size and its recording's wall time.
     */
    private fun stickyRecordings(count: Int = 5): List<Pair<Int, Double>> {
        val names = (0 until 40).map { "dev.sample.T${it.toString().padStart(2, '0')}" }
        var previous: IsolatedPick.Stamp? = null
        var wall = IsolatedPick.Wall(sharedSeconds = 20.0)
        return (1..count).map {
            val alone = previous?.classes?.keys.orEmpty()
            val ranking = IsolatedPick.Ranking(
                scores = names.filter { it !in alone }.associateWith { 100L - names.indexOf(it) },
                isolated = alone,
                seconds = names.associateWith { 1.0 },
                isolatedSeconds = alone.associateWith { 2.0 },
                startOverhead = 1.0,
                sharedJvmSeconds = 16.0,
            )
            val pick = IsolatedPick.pick(ranking, previous, 2.0, hostForks = 1, isolatedForks = 4, processors = 8, wall = wall) { true }
            val isolatedWall = 5.0 * Math.ceil(pick.classes.size / 4.0)
            val took = 20.0 + isolatedWall
            previous = if (pick.classes.isEmpty()) pick else IsolatedPick.measured(pick, isolatedWall, 4)
            wall = if (pick.classes.isEmpty()) wall else wall.copy(budgetedSeconds = took, predictedExtra = pick.predictedExtra, isolatedWallSeconds = isolatedWall)
            pick.classes.size to took
        }
    }

    @Test
    fun `after a first recording over the budget, the pick settles within it and stays`() {
        val runs = stickyRecordings()
        assertTrue(runs.first().second > 40.0, "the first recording is expected over the budget: $runs")
        runs.drop(1).forEach { (_, took) -> assertTrue(took <= 40.0 + 1e-9, "over budget: $runs") }
        runs.drop(1).zipWithNext().forEach { (a, b) -> assertEquals(a.first, b.first, "moves: $runs") }
        assertTrue(runs.last().first > 0, "picks nothing: $runs")
    }

    /** Three picked classes of a 10 s shared recording under 2x, D left shared, after a recording that took [took]. */
    private fun after(took: Double, slot: Double? = null, candidates: Int = 4): IsolatedPick.Stamp {
        val previous = IsolatedPick.Stamp(
            budget = 2.0, startSeconds = 1.0, pickedSeconds = 3.0,
            classes = mapOf("dev.sample.A" to 100L, "dev.sample.B" to 100L, "dev.sample.C" to 50L),
            sharedSeconds = 10.0, overheadSeconds = 0.0, isolatedWallSeconds = 12.0, slotSeconds = slot, candidateClasses = candidates,
        )
        val ranking = IsolatedPick.Ranking(
            scores = mapOf("dev.sample.D" to 10L, "dev.sample.E" to 5L),
            isolated = previous.classes.keys,
            seconds = (previous.classes.keys + listOf("dev.sample.D", "dev.sample.E")).associateWith { 1.0 },
            isolatedSeconds = mapOf("dev.sample.A" to 1.0, "dev.sample.B" to 4.0, "dev.sample.C" to 1.0),
            startOverhead = 1.0,
            sharedJvmSeconds = 10.0,
        )
        val wall = IsolatedPick.Wall(sharedSeconds = 10.0, budgetedSeconds = took, isolatedWallSeconds = 12.0)
        return IsolatedPick.pick(ranking, previous, 2.0, 1, 4, 8, wall) { true }
    }

    @Test
    fun `over the budget, the classes that free the fewest changes per second go first`() {
        // B frees as many changes as A but took four times as long: its 8 s share of the isolated
        // task's 12 s goes first, which brings 26 s under the 20 s budget less its headroom.
        assertEquals(setOf("dev.sample.A", "dev.sample.C"), after(took = 26.0).classes.keys)
    }

    @Test
    fun `within the budget, the pick stays as it was`() {
        assertEquals(setOf("dev.sample.A", "dev.sample.B", "dev.sample.C"), after(took = 19.0, slot = 2.0).classes.keys)
    }

    @Test
    fun `well within the budget, a class is added by score at a measured class's share of a JVM`() {
        assertEquals(setOf("dev.sample.A", "dev.sample.B", "dev.sample.C", "dev.sample.D"), after(took = 15.0, slot = 2.0, candidates = 5).classes.keys)
    }

    @Test
    fun `a task whose classes changed by more than a quarter is picked from scratch`() {
        val scratch = after(took = 19.0, slot = 2.0, candidates = 40)
        assertEquals(5, scratch.candidateClasses)
    }

    @Test
    fun `a pick of nothing is stamped, and the next recording starts from it`(@TempDir dir: File) {
        val empty = IsolatedPick.Stamp(budget = 2.0, startSeconds = 1.0, pickedSeconds = 0.0, classes = emptyMap(), sharedSeconds = 10.0, slotSeconds = 8.0, candidateClasses = 4)
        IsolatedPick.commit(dir, empty)
        val stamped = IsolatedPick.Stamp.read(File(dir, IsolatedPick.STAMP_FILE))!!
        assertEquals(empty, stamped)

        // A shared recording at half the budget adds one class at 8 s over 4 JVMs, where a pick from
        // scratch would take three, one a JVM.
        val ranking = ranking(0.0, 0.0, 0.0, 0.0)
        val next = IsolatedPick.pick(ranking, stamped, 2.0, 1, 4, 8, IsolatedPick.Wall(sharedSeconds = 10.0)) { true }
        assertEquals(1, next.classes.size)
    }

    @Test
    fun `a recording's wall time is recorded as the shared reference or as the budgeted one`(@TempDir dir: File) {
        IsolatedPick.recordWall(dir, null, 20.0)
        assertEquals(IsolatedPick.Wall(sharedSeconds = 20.0), IsolatedPick.Wall.read(dir))

        val pick = IsolatedPick.Stamp(2.0, 1.0, 1.0, mapOf("dev.sample.A" to 1L), predictedExtra = 3.0, isolatedWallSeconds = 9.0)
        IsolatedPick.recordWall(dir, pick, 18.0)
        assertEquals(IsolatedPick.Wall(20.0, 27.0, 3.0, 9.0), IsolatedPick.Wall.read(dir))
    }

    @Test
    fun `the pick leaves the test task a class to run`() {
        val ranking = IsolatedPick.Ranking(mapOf("dev.sample.X" to 2L, "dev.sample.Y" to 1L), emptySet(), mapOf("dev.sample.X" to 1.0, "dev.sample.Y" to 1.0))

        assertEquals(listOf("dev.sample.X"), pick(ranking, 100.0).classes.keys.toList())
    }

    @Test
    fun `a class the test task no longer runs is not picked`(@TempDir dir: File) {
        val ranking = IsolatedPick.rank(map(dir))!!

        val pick = IsolatedPick.pick(ranking, null, 2.2, hostForks = 1, isolatedForks = 1, processors = 8) { it != "dev.sample.CTest" }

        assertEquals(listOf("dev.sample.BTest"), pick.classes.keys.toList())
    }

    @Test
    fun `a test id names its outermost class, a JUnit 4 runner's included`() {
        assertEquals("dev.sample.ATest", IsolatedPick.classOf("[engine:junit-vintage]/[runner:dev.sample.ATest]/[test:a(dev.sample.ATest)]"))
        assertEquals("dev.sample.ATest", IsolatedPick.classOf("[engine:junit-jupiter]/[class:dev.sample.ATest]/[nested-class:Inner]/[method:a()]"))
        assertEquals("dev.sample.ATest", IsolatedPick.classOf("[engine:junit-vintage]/[runner:dev.sample.ATest\$Inner]/[test:a]"))
        assertNull(IsolatedPick.classOf("[yoriwake:unattributed]"))
    }

    @Test
    fun `the measured start cost counts no more JVMs than there were classes`() {
        val stamp = IsolatedPick.Stamp(2.0, 1.0, pickedSeconds = 4.0, classes = mapOf("dev.sample.A" to 3L, "dev.sample.B" to 1L))

        // Two classes on 16 forks ran on two JVMs: 5 s each, less their 4 s, over two classes.
        assertEquals(3.0, IsolatedPick.measured(stamp, wallSeconds = 5.0, forks = 16).measuredStartSeconds)
    }

    @Test
    fun `the hand-off leaves out a debugger agent`() {
        assertEquals(
            "-Xmx1g\n-javaagent:a.jar\n",
            IsolatedPick.handOff(listOf("-Xmx1g", "-agentlib:jdwp=transport=dt_socket,server=y,address=5005", "-Xrunjdwp:x", "-javaagent:a.jar")),
        )
    }

    @Test
    fun `a budget of 1x picks nothing`(@TempDir dir: File) {
        assertEquals(emptyMap(), pick(IsolatedPick.rank(map(dir))!!, 1.0).classes)
    }

    @Test
    fun `a class the previous map recorded alone keeps the score it was picked with`(@TempDir dir: File) {
        val first = pick(IsolatedPick.rank(map(dir))!!, 2.2)
        val stamped = File(dir, "stamp").apply { writeText(IsolatedPick.measured(first, wallSeconds = 3.5, forks = 4).encode()) }
        val again = IsolatedPick.Stamp.read(stamped)!!
        assertEquals(1.5, again.measuredStartSeconds)

        val ranking = IsolatedPick.rank(map(File(dir, "next").apply { mkdirs() }, cTestAlone = true))!!
        val second = pick(ranking, 2.2, previous = again)

        assertEquals(mapOf("dev.sample.CTest" to 5L), second.classes)
        // The measured start cost is kept for the record, not fed back into the next pick.
        assertEquals(IsolatedPick.DEFAULT_START_SECONDS, second.startSeconds)
    }

    @Test
    fun `a class that ran alone in a JVM labelled shared keeps the score it was picked with`(@TempDir dir: File) {
        val first = pick(IsolatedPick.rank(map(dir))!!, 2.2)

        // A parameterized class's test ids name no class, so the decoder labels its own JVM shared.
        val ranking = IsolatedPick.rank(map(File(dir, "next").apply { mkdirs() }, cTestAlone = true, cJvmLabel = "shared"))!!
        val second = pick(ranking, 2.2, previous = first)

        assertEquals(mapOf("dev.sample.CTest" to 5L), second.classes)
    }

    @Test
    fun `a class recorded alone, slower than it ran shared, costs what it did when picked`(@TempDir dir: File) {
        val first = pick(IsolatedPick.rank(map(dir))!!, 2.2)
        val stamped = IsolatedPick.Stamp.read(File(dir, "stamp").apply { writeText(first.encode()) })!!

        // Alone and cold, CTest's tests took three times as long.
        val ranking = IsolatedPick.rank(map(File(dir, "next").apply { mkdirs() }, cTestAlone = true, cNanos = 3000000000))!!
        val second = pick(ranking, 2.2, previous = stamped)

        assertEquals(first.classes, second.classes)
        assertEquals(first.sharedSeconds, second.sharedSeconds)
        assertEquals(first.classSeconds, second.classSeconds)
    }

    @Test
    fun `a budget is a multiple of 1 or more`() {
        assertEquals(2.0, IsolatedPick.parseBudget("2x"))
        assertEquals(1.5, IsolatedPick.parseBudget("1.5"))
        assertNull(IsolatedPick.parseBudget("0.9x"))
        assertNull(IsolatedPick.parseBudget("twice"))
        assertEquals("2x", IsolatedPick.describe(2.0))
        assertEquals("1.5x", IsolatedPick.describe(1.5))
    }
}
