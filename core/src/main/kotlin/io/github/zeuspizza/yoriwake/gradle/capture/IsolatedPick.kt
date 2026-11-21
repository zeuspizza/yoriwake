package io.github.zeuspizza.yoriwake.gradle.capture

import io.github.zeuspizza.yoriwake.agent.contract.AgentContract
import io.github.zeuspizza.yoriwake.agent.contract.TestClassIds
import io.github.zeuspizza.yoriwake.agent.contract.Tsv
import io.github.zeuspizza.yoriwake.gradle.SettingNames
import java.io.File

/**
 * `yoriwake.isolatedCaptureBudget`: which test classes a recording run gives a JVM of their own,
 * within a wall-clock budget over a shared recording. The classes are ranked from the map the run
 * is about to replace, by how many changes would reach their tests only through what a shared JVM
 * loaded before them, and what was picked is stamped beside the map ([STAMP_FILE]).
 */
internal object IsolatedPick {

    /** Beside the map: the budget, the JVM start cost and each picked class with its score. */
    const val STAMP_FILE = "isolated-pick"

    /** This run's pick, until its decode makes it the map's [STAMP_FILE]. */
    const val PENDING_FILE = "isolated-pick.pending"

    /** What one more test JVM is taken to cost until a budgeted recording has measured it. */
    const val DEFAULT_START_SECONDS = 1.0

    /** The isolated part's parallel JVMs when `yoriwake.isolatedCaptureForks` does not say. */
    const val DEFAULT_FORKS = 4

    /** A budget of 1 or more, as `<n>x` or `<n>`; null for anything else. */
    fun parseBudget(value: String): Double? =
        value.trim().removeSuffix("x").removeSuffix("X").toDoubleOrNull()?.takeIf { it >= 1.0 && it.isFinite() }

    /** What a malformed [value], spelled [flag] (`-P` or `-D`), is told. */
    fun malformed(flag: String, value: String) =
        "[yoriwake] $flag${SettingNames.ISOLATED_CAPTURE_BUDGET}=$value is not a budget. Pass how many times a " +
            "shared recording the recording may take, 1 or more, such as 2x or 1.5x."

    /** [budget] as the flag spells it: `2x`, `1.5x`. */
    fun describe(budget: Double): String =
        (if (budget == Math.floor(budget)) budget.toLong().toString() else budget.toString()) + "x"

    /** The class file pattern of [className] for a test task's scan, and of its nested classes. */
    fun scanPatterns(className: String): List<String> {
        val path = className.replace('.', '/')
        return listOf("$path.class", "$path\$*.class")
    }

    /**
     * [arguments] as the hand-off file holds them, one escaped per line, without a debugger agent:
     * the isolated JVMs run in parallel and would all listen on its one port.
     */
    fun handOff(arguments: List<String>): String =
        arguments.filterNot { it.startsWith("-agentlib:jdwp") || it.startsWith("-Xrunjdwp") }
            .joinToString("") { Tsv.escape(it) + "\n" }

    /** The test class [testId] belongs to, outermost, or null when it names none. */
    fun classOf(testId: String): String? {
        val name = TestClassIds.nameOf(TestClassIds.scopeOf(testId))
            ?: RUNNER.find(testId)?.groupValues?.get(1)
            ?: return null
        return name.substringBefore('$').takeIf(String::isNotEmpty)
    }

    private val RUNNER = Regex("""\[runner:([^\]]+)]""")

    /**
     * What a pick needs from a map: each test class's score from the JVMs it shared, the classes
     * that ran only in JVMs of their own, and each class's recorded test seconds. Where the map
     * measured its JVMs ([CoverageDecoder.JVM_SECONDS_FILE]): what each class's own JVMs took, the
     * mean start overhead of a shared JVM (its time less its tests'), and the shared JVMs' time.
     */
    class Ranking(
        val scores: Map<String, Long>,
        val isolated: Set<String>,
        val seconds: Map<String, Double>,
        val isolatedSeconds: Map<String, Double> = emptyMap(),
        val startOverhead: Double? = null,
        val sharedJvmSeconds: Double? = null,
    )

    /**
     * Ranks the test classes of the map in [mapDir], or null when there is no map of this schema.
     * Per class, the sum over its tests in shared JVMs of the in-scope classes that JVM had first
     * touched at or before the test, less the classes the test's own coverage holds, never below 0:
     * how many one-class changes would reach the test only through its JVM.
     */
    fun rank(mapDir: File): Ranking? {
        val version = File(mapDir, AgentContract.MAP_SCHEMA_VERSION_FILE).takeIf(File::isFile)?.readText()?.trim()
        val coverageFile = File(mapDir, AgentContract.COVERAGE_FILE)
        if (version != "${AgentContract.MAP_SCHEMA_VERSION}" || !coverageFile.isFile) return null
        val rows = { name: String ->
            File(mapDir, name).takeIf(File::isFile)?.readLines().orEmpty().filter(String::isNotBlank).map(Tsv::split)
        }
        val scope = rows(AgentContract.SCOPE_FILE).map { it[0].trim() }.filter(String::isNotEmpty)
        val inScope = { name: String -> scope.isEmpty() || scope.any { name == it || name.startsWith("$it.") } }
        val isolatedJvms = rows(AgentContract.JVM_MODE_FILE)
            .filter { it.size == 2 && it[1] == AgentContract.MODE_ISOLATED }.mapTo(HashSet()) { it[0] }

        val covered = HashMap<String, MutableSet<String>>()
        val seconds = HashMap<String, Double>()
        rows(AgentContract.COVERAGE_FILE).filter { it.size >= 4 }.forEach { row ->
            val id = row[3]
            covered.getOrPut(id) { HashSet() }.addAll(row[2].split(',').filter(String::isNotEmpty))
            val owner = classOf(id) ?: return@forEach
            val nanos = row[1].toLongOrNull() ?: 0L
            seconds[owner] = (seconds[owner] ?: 0.0) + nanos / 1e9
        }

        val touches = HashMap<String, MutableList<Long>>()
        rows(AgentContract.FIRST_TOUCH_FILE).filter { it.size >= 3 }.forEach { row ->
            val sequence = row[1].toLongOrNull() ?: return@forEach
            if (row[2] != AgentContract.FIRST_TOUCH_ANY && inScope(row[2])) touches.getOrPut(row[0]) { ArrayList() }.add(sequence)
        }
        touches.values.forEach { it.sort() }

        val scores = HashMap<String, Long>()
        val shared = HashSet<String>()
        val alone = HashSet<String>()
        val positions = rows(AgentContract.POSITIONS_FILE).filter { it.size >= 3 }
        // A JVM that ran one class keeps no other class's windows, whatever its label: the decoder
        // labels a JVM shared when a test id names no class, as a parameterized one's does.
        val classesByJvm = HashMap<String, MutableSet<String?>>()
        positions.forEach { classesByJvm.getOrPut(it[0]) { HashSet() }.add(classOf(it[2])) }
        val oneClass = classesByJvm.filterValues { it.size == 1 && null !in it }.keys
        positions.forEach { row ->
            val owner = classOf(row[2]) ?: return@forEach
            val sequence = row[1].toLongOrNull() ?: return@forEach
            if (row[0] in isolatedJvms || row[0] in oneClass) {
                alone.add(owner)
                return@forEach
            }
            shared.add(owner)
            val before = touches[row[0]]?.let { atOrBefore(it, sequence) } ?: 0
            val own = covered[row[2]]?.size ?: 0
            scores[owner] = (scores[owner] ?: 0L) + maxOf(before - own, 0)
        }
        // Measured JVM time, a JVM's later test plans folded into the JVM that ran them.
        val testNanos = HashMap<String, Long>()
        rows(AgentContract.COVERAGE_FILE).filter { it.size >= 4 }.forEach { testNanos.merge(it[3], it[1].toLongOrNull() ?: 0L, Long::plus) }
        val jvmOf = { id: String -> id.replace(LATER_PLAN, "") }
        val uptime = HashMap<String, Double>()
        rows(CoverageDecoder.JVM_SECONDS_FILE).filter { it.size >= 2 }.forEach { row ->
            row[1].toDoubleOrNull()?.let { uptime.merge(jvmOf(row[0]), it, ::maxOf) }
        }
        val testsByJvm = HashMap<String, MutableSet<String>>()
        val classesOf = HashMap<String, MutableSet<String?>>()
        positions.forEach {
            testsByJvm.getOrPut(jvmOf(it[0])) { HashSet() }.add(it[2])
            classesOf.getOrPut(jvmOf(it[0])) { HashSet() }.add(classOf(it[2]))
        }
        val isolatedSeconds = HashMap<String, Double>()
        val overheads = ArrayList<Double>()
        var sharedJvmSeconds = 0.0
        uptime.forEach { (jvm, took) ->
            val classes = classesOf[jvm] ?: return@forEach
            val only = classes.singleOrNull()
            if (only != null) {
                isolatedSeconds.merge(only, took, Double::plus)
            } else {
                sharedJvmSeconds += took
                overheads += maxOf(0.0, took - testsByJvm.getValue(jvm).sumOf { testNanos[it] ?: 0L } / 1e9)
            }
        }
        return Ranking(
            scores, alone - shared, seconds, isolatedSeconds,
            overheads.takeIf { it.isNotEmpty() }?.average(), sharedJvmSeconds.takeIf { overheads.isNotEmpty() },
        )
    }

    /** Ends the id of a JVM's second and later test plans; see [AgentContract.PLAN_DIR_SUFFIX]. */
    private val LATER_PLAN = Regex("${Regex.escape(AgentContract.PLAN_DIR_SUFFIX)}\\d+$")

    /** How many of the sorted [sequences] are at or before [sequence]. */
    private fun atOrBefore(sequences: List<Long>, sequence: Long): Int {
        var low = 0
        var high = sequences.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (sequences[middle] <= sequence) low = middle + 1 else high = middle
        }
        return low
    }

    /**
     * What a recording picked: each class with its score, in pick order, the budget it was picked
     * under, the JVM start cost it assumed, the picked classes' recorded seconds, and the start cost
     * its own isolated run measured once it ran.
     */
    data class Stamp(
        val budget: Double,
        val startSeconds: Double,
        val pickedSeconds: Double,
        val classes: Map<String, Long>,
        val measuredStartSeconds: Double? = null,
        /** The shared recording the budget was a multiple of, kept while classes stay picked. */
        val sharedSeconds: Double? = null,
        /** What each picked class was costed at when it was picked, its JVM's start included. */
        val classSeconds: Map<String, Double> = emptyMap(),
        /** The test task's wall time outside its JVMs, kept while classes stay picked. */
        val overheadSeconds: Double? = null,
        /** The picked classes' costs over the JVMs they run on, before calibration. */
        val predictedExtra: Double? = null,
        /** The calibration the pick applied to each cost; see [pick]. */
        val factor: Double = 1.0,
        /** The wall time the isolated task took, once it ran. */
        val isolatedWallSeconds: Double? = null,
    ) {
        fun encode(): String = buildString {
            append(Tsv.join("budget", budget.toString())).append('\n')
            append(Tsv.join("start-seconds", startSeconds.toString())).append('\n')
            append(Tsv.join("picked-seconds", pickedSeconds.toString())).append('\n')
            measuredStartSeconds?.let { append(Tsv.join("measured-start-seconds", it.toString())).append('\n') }
            sharedSeconds?.let { append(Tsv.join("shared-seconds", it.toString())).append('\n') }
            overheadSeconds?.let { append(Tsv.join("overhead-seconds", it.toString())).append('\n') }
            predictedExtra?.let { append(Tsv.join("predicted-extra", it.toString())).append('\n') }
            append(Tsv.join("factor", factor.toString())).append('\n')
            isolatedWallSeconds?.let { append(Tsv.join("isolated-wall-seconds", it.toString())).append('\n') }
            classes.forEach { (name, score) ->
                val seconds = classSeconds[name]
                append(if (seconds == null) Tsv.join("class", score.toString(), name) else Tsv.join("class", score.toString(), name, seconds.toString())).append('\n')
            }
        }

        companion object {
            /** The stamp [file] holds, or null when it is missing or does not read as one. */
            fun read(file: File): Stamp? = runCatching {
                if (!file.isFile) return null
                val rows = file.readLines().filter(String::isNotBlank).map(Tsv::split)
                val value = { key: String -> rows.firstOrNull { it[0] == key }?.getOrNull(1)?.toDouble() }
                Stamp(
                    budget = value("budget") ?: return null,
                    startSeconds = value("start-seconds") ?: return null,
                    pickedSeconds = value("picked-seconds") ?: return null,
                    classes = rows.filter { it[0] == "class" && it.size >= 3 }.associate { it[2] to it[1].toLong() },
                    measuredStartSeconds = value("measured-start-seconds"),
                    sharedSeconds = value("shared-seconds"),
                    classSeconds = rows.filter { it[0] == "class" && it.size >= 4 }.associate { it[2] to it[3].toDouble() },
                    overheadSeconds = value("overhead-seconds"),
                    predictedExtra = value("predicted-extra"),
                    factor = value("factor") ?: 1.0,
                    isolatedWallSeconds = value("isolated-wall-seconds"),
                )
            }.getOrNull()
        }
    }

    /**
     * The wall time of a task's recordings, beside the map ([WALL_FILE]): the last fully shared
     * recording's test task ([sharedSeconds], kept while classes stay picked), and the last
     * budgeted recording's test task and isolated task together, with the extra its pick predicted.
     */
    data class Wall(
        val sharedSeconds: Double? = null,
        val budgetedSeconds: Double? = null,
        val predictedExtra: Double? = null,
    ) {
        fun encode(): String = listOfNotNull(
            sharedSeconds?.let { Tsv.join("shared-wall-seconds", it.toString()) },
            budgetedSeconds?.let { Tsv.join("budgeted-wall-seconds", it.toString()) },
            predictedExtra?.let { Tsv.join("predicted-extra", it.toString()) },
        ).joinToString("") { "$it\n" }

        companion object {
            /** The record beside the map in [mapDir]; empty when there is none. */
            fun read(mapDir: File): Wall = runCatching {
                val rows = File(mapDir, WALL_FILE).takeIf(File::isFile)?.readLines().orEmpty()
                    .filter(String::isNotBlank).map(Tsv::split)
                val value = { key: String -> rows.firstOrNull { it[0] == key }?.getOrNull(1)?.toDoubleOrNull() }
                Wall(value("shared-wall-seconds"), value("budgeted-wall-seconds"), value("predicted-extra"))
            }.getOrDefault(Wall())
        }
    }

    /** Beside the map: see [Wall]. */
    const val WALL_FILE = "recording-wall"

    /**
     * Records the wall time of the recording that just wrote the map: [testWallSeconds], its test
     * task's, and [pick], what it ran alone and how long that took. A fully shared recording is the
     * new shared reference; a budgeted one whose isolated task ran is the calibration's next point.
     */
    fun recordWall(mapDir: File, pick: Stamp?, testWallSeconds: Double?) {
        testWallSeconds ?: return
        val old = Wall.read(mapDir)
        val next = when {
            pick == null || pick.classes.isEmpty() -> old.copy(sharedSeconds = testWallSeconds)
            pick.isolatedWallSeconds != null && pick.predictedExtra != null ->
                old.copy(budgetedSeconds = testWallSeconds + pick.isolatedWallSeconds, predictedExtra = pick.predictedExtra)
            else -> return
        }
        writeAtomically(File(mapDir, WALL_FILE), next.encode())
    }

    /**
     * The classes to record alone: by score, highest first, while their cost fits the budget. A
     * class costs what its own JVM took when the map measured it alone; else a shared JVM's measured
     * start overhead plus twice its recorded test time, cold as a fresh JVM runs (1 second plus twice,
     * where nothing was measured). The picked classes are taken to run on min([isolatedForks],
     * [processors]) JVMs, each to the least loaded, and to take as long as the slowest of those.
     *
     * The budget allows (budget - 1) times the shared recording: its test task's wall time when
     * [wall] has it, else its JVMs' measured time, else its tests' recorded time, over [hostForks].
     * Calibrated against wall time: the test task's time outside its JVMs is charged once, and each
     * cost is multiplied by what the last budgeted recording's extra wall time, less that, was over
     * what its picked classes cost now, as this pick costs them (over the extra that pick predicted
     * when the wall record is not [previous]'s own). Never below 1; 1 when no budgeted recording was
     * measured. Once [previous] was calibrated above 1, the factor moves only halfway (the geometric
     * mean of its factor and the new one), so one recording's noise does not swing the pick.
     *
     * Stable across recordings: a class [previous] picked that ran alone keeps the score it was
     * picked with, and, unless the map measured its JVM, the cost; the shared recording stays the one
     * it was picked against. Only classes [runnable] says the test task still has, and whose score
     * is above 0, are picked, and never all of those: the test task keeps tests to run.
     */
    fun pick(
        ranking: Ranking,
        previous: Stamp?,
        budget: Double,
        hostForks: Int,
        isolatedForks: Int,
        processors: Int,
        wall: Wall = Wall(),
        runnable: (String) -> Boolean,
    ): Stamp {
        val startSeconds = ranking.startOverhead ?: DEFAULT_START_SECONDS
        val scores = HashMap(ranking.scores)
        val carried = previous?.classes?.keys.orEmpty().filter { it in ranking.isolated }.toSet()
        carried.forEach { scores[it] = previous!!.classes.getValue(it) }
        val cost = { name: String ->
            ranking.isolatedSeconds[name]
                ?: previous?.classSeconds?.get(name)?.takeIf { name in carried }
                ?: (startSeconds + COLD * (ranking.seconds[name] ?: 0.0))
        }
        val shared = wall.sharedSeconds
            ?: previous?.sharedSeconds?.takeIf { carried.isNotEmpty() }
            ?: ((ranking.sharedJvmSeconds ?: ranking.seconds.values.sum()) / maxOf(1, hostForks))
        // Outside the JVMs: what the shared recording's wall time holds beyond its JVMs' time, read
        // off the map it recorded, and kept while classes stay picked, since the map then holds fewer.
        val overhead = if (carried.isNotEmpty()) previous?.overheadSeconds ?: 0.0 else {
            val jvms = ranking.sharedJvmSeconds
            if (wall.sharedSeconds != null && jvms != null) maxOf(0.0, wall.sharedSeconds - jvms / maxOf(1, hostForks)) else 0.0
        }
        val parallel = maxOf(1, minOf(isolatedForks, processors))
        // The calibration compares like with like: a class the previous pick estimated is costed now
        // at what its own JVM took, so the previous pick is re-predicted at those costs, when the wall
        // record is that pick's own recording and its JVMs were measured.
        val ownRecording = previous?.isolatedWallSeconds != null && wall.predictedExtra == previous.predictedExtra &&
            previous.classes.keys.any { it in ranking.isolatedSeconds }
        val predicted = if (ownRecording) {
            makespan(previous!!.classes.keys.map { ranking.isolatedSeconds[it] ?: previous.classSeconds[it] ?: cost(it) }, parallel)
        } else {
            wall.predictedExtra
        }
        val ratio = if (wall.sharedSeconds != null && wall.budgetedSeconds != null && predicted != null && predicted > 0) {
            maxOf(1.0, (wall.budgetedSeconds - wall.sharedSeconds - overhead) / predicted)
        } else {
            null
        }
        val factor = when {
            ratio == null -> 1.0
            ownRecording && previous!!.factor > 1.0 -> Math.sqrt(previous.factor * ratio)
            else -> ratio
        }
        val allowance = (budget - 1.0) * shared - overhead
        val left = (ranking.scores.keys + ranking.isolated).count(runnable)
        val picked = LinkedHashMap<String, Long>()
        val costs = LinkedHashMap<String, Double>()
        for ((name, score) in scores.entries.sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key })) {
            if (score <= 0 || !runnable(name)) continue
            if (picked.size + 1 >= left) break
            if (factor * makespan(costs.values + cost(name), parallel) > allowance) break
            picked[name] = score
            costs[name] = cost(name)
        }
        return Stamp(
            budget, startSeconds, picked.keys.sumOf { ranking.seconds[it] ?: 0.0 }, picked, sharedSeconds = shared,
            classSeconds = costs, overheadSeconds = overhead, predictedExtra = makespan(costs.values, parallel), factor = factor,
        )
    }

    /** How long [costs] take on [parallel] JVMs, each in turn to the least loaded one. */
    private fun makespan(costs: Collection<Double>, parallel: Int): Double {
        val loads = DoubleArray(maxOf(1, parallel))
        costs.forEach { cost -> loads[loads.indices.minByOrNull { loads[it] }!!] += cost }
        return loads.maxOrNull() ?: 0.0
    }

    /** How much slower a class's tests are taken to run in a fresh JVM than in a warm shared one. */
    private const val COLD = 2.0

    /**
     * [stamp] with the JVM start cost its isolated run measured: [wallSeconds] over the [forks] JVMs
     * it ran at once (no more than it had classes), less the picked classes' own seconds, per class;
     * never below 0.
     */
    fun measured(stamp: Stamp, wallSeconds: Double, forks: Int): Stamp {
        if (stamp.classes.isEmpty()) return stamp
        val used = maxOf(1, minOf(forks, stamp.classes.size))
        return stamp.copy(
            measuredStartSeconds = maxOf(0.0, (wallSeconds * used - stamp.pickedSeconds) / stamp.classes.size),
            isolatedWallSeconds = wallSeconds,
        )
    }

    /**
     * Reads and removes this run's pending pick. Its decode makes it the map's stamp once the map is
     * written ([commit]); any other outcome leaves the stamp the map had.
     */
    fun takePending(mapDir: File): Stamp? {
        val file = File(mapDir, PENDING_FILE)
        return Stamp.read(file).also { runCatching { file.delete() } }
    }

    /** Stamps the map just written with [pick], or removes its stamp when the run picked nothing. */
    fun commit(mapDir: File, pick: Stamp?) {
        val file = File(mapDir, STAMP_FILE)
        if (pick == null || pick.classes.isEmpty()) file.delete() else writeAtomically(file, pick.encode())
    }

    /** The line `yoriwakeExplain` prints about the map's stamp. */
    fun explain(stamp: Stamp): String =
        "${stamp.classes.size} test classes recorded in their own JVM, picked under budget ${describe(stamp.budget)}"
}
