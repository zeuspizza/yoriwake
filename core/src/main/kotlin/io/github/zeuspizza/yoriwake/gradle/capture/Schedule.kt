package io.github.zeuspizza.yoriwake.gradle.capture

import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * How long a map has gone without a run that executed every test, for a build that bounds it with
 * `fullRunEveryBuilds` or `fullRunEveryDays`: past the bound, a selecting run runs everything, so a
 * gap in the map cannot hide for longer. Two plugin-private files beside the map travel with it.
 */
object Schedule {

    /** The build's `fullRunEveryBuilds` and `fullRunEveryDays`, each null when unset. */
    data class Limits(val builds: Int?, val days: Int?) {
        val set: Boolean get() = builds != null || days != null
    }

    /** The test task's executions since the last full run, as one integer. */
    const val COUNT_FILE = "runs-since-full-run"

    /** When the last full run happened, as one ISO-8601 instant. */
    const val TIME_FILE = "full-run-time"

    /** Clock skew between the machine that ran in full and this one, accepted before it reads as due. */
    private val FUTURE_SLACK: Duration = Duration.ofDays(1)

    /**
     * Records one execution of the test task: a full run resets the count and the time, any other
     * adds one. An unreadable count is left as it is, since a guessed one would postpone the bound.
     * Null when written, else why not; never throws, so the decode never fails over it.
     */
    fun record(mapDir: File, fullRun: Boolean, now: Instant = Instant.now()): String? = runCatching {
        if (fullRun) {
            writeAtomically(File(mapDir, TIME_FILE), "$now\n")
            writeAtomically(File(mapDir, COUNT_FILE), "0\n")
        } else {
            countIn(read(File(mapDir, COUNT_FILE)))?.let { writeAtomically(File(mapDir, COUNT_FILE), "${it + 1}\n") }
        }
    }.exceptionOrNull()?.toString()

    /**
     * Why a run asked to select must run everything now, or null. Either limit reached is due, the
     * builds limit named first; a count or time that cannot be read is due, as is a map that holds
     * neither file. With no map there is nothing to bound: the run records anyway.
     */
    fun due(
        mapExists: Boolean,
        count: String?,
        time: String?,
        captureCommit: String?,
        now: Instant,
        builds: Int?,
        days: Int?,
    ): String? {
        if (!mapExists) return null
        val recorded = captureCommit?.trim()?.takeIf(String::isNotEmpty)?.let { "; map recorded at ${it.take(12)}" } ?: ""
        if (builds != null) {
            val n = countIn(count) ?: return "the count of builds since the last full run ($COUNT_FILE) could not be read"
            if (n + 1 >= builds) return "${n + 1} builds since the last full run$recorded (limit $builds)"
        }
        if (days != null) {
            val last = instantIn(time) ?: return "the time of the last full run ($TIME_FILE) could not be read"
            if (last.isAfter(now.plus(FUTURE_SLACK))) {
                return "the time of the last full run ($TIME_FILE) lies in the future, at $last"
            }
            val elapsed = Duration.between(last, now)
            if (elapsed >= Duration.ofDays(days.toLong())) {
                return "${elapsed.toDays()} days since the last full run$recorded (limit $days)"
            }
        }
        return null
    }

    /** How far away the next scheduled full run is, for a run that is not due; null with no limit. */
    fun untilDue(count: String?, time: String?, builds: Int?, days: Int?): String? {
        val parts = listOfNotNull(
            builds?.let { limit -> countIn(count)?.let { limit - 1 - it }?.let { "in $it build${if (it == 1) "" else "s"}" } },
            days?.let { limit -> instantIn(time)?.let { "by ${it.plus(Duration.ofDays(limit.toLong())).atOffset(ZoneOffset.UTC).toLocalDate()}" } },
        )
        return if (parts.isEmpty()) null else "full run due ${parts.joinToString(" or ")}"
    }

    /** [due] for the map in [mapDir], read from its files. */
    fun dueIn(mapDir: File, builds: Int?, days: Int?, now: Instant = Instant.now()): String? = due(
        mapExists = File(mapDir, io.github.zeuspizza.yoriwake.agent.contract.AgentContract.MAP_SCHEMA_VERSION_FILE).isFile,
        count = read(File(mapDir, COUNT_FILE)),
        time = read(File(mapDir, TIME_FILE)),
        captureCommit = read(File(mapDir, CoverageDecoder.CAPTURE_COMMIT_FILE))?.lineSequence()?.firstOrNull(),
        now = now,
        builds = builds,
        days = days,
    )

    /** The file's text, or null when it is absent or cannot be read. */
    fun read(file: File): String? = runCatching { file.takeIf(File::isFile)?.readText() }.getOrNull()

    private fun countIn(text: String?): Int? = text?.trim()?.toIntOrNull()?.takeIf { it >= 0 }

    private fun instantIn(text: String?): Instant? = text?.trim()?.let { runCatching { Instant.parse(it) }.getOrNull() }
}
