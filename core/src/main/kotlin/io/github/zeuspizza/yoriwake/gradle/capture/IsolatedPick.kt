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
     * that ran only in JVMs of their own, and each class's recorded seconds.
     */
    class Ranking(
        val scores: Map<String, Long>,
        val isolated: Set<String>,
        val seconds: Map<String, Double>,
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
        return Ranking(scores, alone - shared, seconds)
    }

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
        /** Each picked class's recorded seconds when it was picked, from its shared JVM. */
        val classSeconds: Map<String, Double> = emptyMap(),
    ) {
        fun encode(): String = buildString {
            append(Tsv.join("budget", budget.toString())).append('\n')
            append(Tsv.join("start-seconds", startSeconds.toString())).append('\n')
            append(Tsv.join("picked-seconds", pickedSeconds.toString())).append('\n')
            measuredStartSeconds?.let { append(Tsv.join("measured-start-seconds", it.toString())).append('\n') }
            sharedSeconds?.let { append(Tsv.join("shared-seconds", it.toString())).append('\n') }
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
                )
            }.getOrNull()
        }
    }

    /**
     * The classes to record alone: by score, highest first, while their extra cost fits the budget.
     * Recording a class alone costs a JVM start plus its tests' seconds, spread over
     * min([isolatedForks], [processors]) JVMs; the budget allows (budget - 1) times the shared
     * recording, its classes' seconds over [hostForks]. A class [previous] picked that the map
     * recorded alone keeps the score it was picked with. Only classes [runnable] says the test task
     * still has, and whose score is above 0, are picked, and never all of those: the test task keeps
     * tests to run.
     */
    fun pick(
        ranking: Ranking,
        previous: Stamp?,
        budget: Double,
        hostForks: Int,
        isolatedForks: Int,
        processors: Int,
        runnable: (String) -> Boolean,
    ): Stamp {
        // Not the start cost the previous run measured: that includes the isolated task's own start,
        // which a small pick cannot spread, so feeding it back shrank the pick on every recording.
        val startSeconds = DEFAULT_START_SECONDS
        val scores = HashMap(ranking.scores)
        val carried = previous?.classes?.keys.orEmpty().filter { it in ranking.isolated }.toSet()
        carried.forEach { scores[it] = previous!!.classes.getValue(it) }
        // A class recorded alone runs cold and slower than it did shared; costing it, and the shared
        // recording, from that run would grow the pick on every recording. Both stay as first picked.
        val seconds = { name: String -> previous?.classSeconds?.get(name)?.takeIf { name in carried } ?: ranking.seconds[name] ?: 0.0 }
        val shared = previous?.sharedSeconds?.takeIf { carried.isNotEmpty() }
            ?: (ranking.seconds.values.sum() / maxOf(1, hostForks))
        val allowance = (budget - 1.0) * shared
        val parallel = maxOf(1, minOf(isolatedForks, processors))
        val left = (ranking.scores.keys + ranking.isolated).count(runnable)
        val picked = LinkedHashMap<String, Long>()
        var spent = 0.0
        var pickedSeconds = 0.0
        for ((name, score) in scores.entries.sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key })) {
            if (score <= 0 || !runnable(name)) continue
            if (picked.size + 1 >= left) break
            val own = seconds(name)
            if ((spent + startSeconds + own) / parallel > allowance) break
            spent += startSeconds + own
            pickedSeconds += own
            picked[name] = score
        }
        return Stamp(budget, startSeconds, pickedSeconds, picked, sharedSeconds = shared,
            classSeconds = picked.keys.associateWith(seconds))
    }

    /**
     * [stamp] with the JVM start cost its isolated run measured: [wallSeconds] over the [forks] JVMs
     * it ran at once (no more than it had classes), less the picked classes' own seconds, per class;
     * never below 0.
     */
    fun measured(stamp: Stamp, wallSeconds: Double, forks: Int): Stamp {
        if (stamp.classes.isEmpty()) return stamp
        val used = maxOf(1, minOf(forks, stamp.classes.size))
        return stamp.copy(measuredStartSeconds = maxOf(0.0, (wallSeconds * used - stamp.pickedSeconds) / stamp.classes.size))
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
