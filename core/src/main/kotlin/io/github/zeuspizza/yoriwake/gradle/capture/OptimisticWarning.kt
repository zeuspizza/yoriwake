package io.github.zeuspizza.yoriwake.gradle.capture

import io.github.zeuspizza.yoriwake.agent.contract.AgentContract
import io.github.zeuspizza.yoriwake.agent.contract.Tsv
import io.github.zeuspizza.yoriwake.agent.select.Strictness
import io.github.zeuspizza.yoriwake.agent.select.Verdict
import io.github.zeuspizza.yoriwake.gradle.change.RefusalKind
import java.io.File

/**
 * The line every optimistic selecting run prints once per test task: how many tests the safe rule
 * runs it left out, summed over every test JVM, and the reminder to keep a full run.
 */
internal object OptimisticWarning {

    /**
     * The distinct tests this run's decision records name [Verdict.LOOSENED], or null when a record
     * cannot be read whole: a count that may have lost rows is not printed as one.
     */
    fun loosened(mapDir: File): Int? {
        val parts = parts(mapDir)
        val loosened = HashSet<String>()
        for (part in parts) {
            val lines = runCatching { part.readLines() }.getOrNull() ?: return null
            var declared: Int? = null
            var rows = 0
            for (line in lines) {
                when {
                    line.startsWith(AgentContract.NOTE_PREFIX) -> {
                        val fields = Tsv.split(line.removePrefix(AgentContract.NOTE_PREFIX))
                        if (fields[0] == AgentContract.ROWS_NOTE) declared = fields.getOrNull(1)?.toIntOrNull()
                    }
                    line.isBlank() || line.startsWith("#") -> Unit
                    else -> {
                        rows++
                        val fields = Tsv.split(line)
                        if (fields.getOrNull(2) == Verdict.LOOSENED.reasonToken()) loosened += fields[0]
                    }
                }
            }
            if (declared == null || declared != rows) return null
        }
        return loosened.size
    }

    /** The decline this run's decision records note, when it declined to select as it started, or null. */
    fun declined(mapDir: File): String? = parts(mapDir).firstNotNullOfOrNull { part ->
        runCatching { part.readLines() }.getOrDefault(emptyList())
            .filter { it.startsWith(AgentContract.NOTE_PREFIX) }
            .map { Tsv.split(it.removePrefix(AgentContract.NOTE_PREFIX)) }
            .firstOrNull { it[0] == AgentContract.REFUSAL_KIND_NOTE && it.getOrNull(1) in DECLINES }
            ?.let { "selection was declined (${it[1]})" }
    }

    private val DECLINES = setOf(RefusalKind.TESTS_NAMED, RefusalKind.DECLINE_UNDETERMINED).map { it.token }

    private fun parts(mapDir: File): List<File> = mapDir.listFiles { file ->
        file.name.startsWith("${AgentContract.DECISIONS_FILE}.") &&
            file.name.endsWith(AgentContract.DECISIONS_PART_SUFFIX)
    }.orEmpty().toList()

    /**
     * The line for [taskPath]. [fullRunPolicy] says whether the build lists a full-run branch or sets
     * a scheduled full run; without either, the line says nothing will run the rest.
     */
    fun line(taskPath: String, loosened: Int?, fullRunPolicy: Boolean): String {
        val rules = Strictness.OPTIMISTIC.loosened().joinToString(", ") { it.token() }
        val count = loosened?.toString() ?: "an unknown number of"
        return "[yoriwake] $taskPath: optimistic mode: $count tests the safe rule runs were left out ($rules). " +
            "Keep a full run after merge, nightly or before a release." +
            if (fullRunPolicy) "" else
                " This build sets no full-run policy (fullRunBranches, fullRunEveryBuilds or fullRunEveryDays), " +
                    "so nothing here runs them."
    }
}
