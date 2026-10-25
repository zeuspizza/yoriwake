package io.github.zeuspizza.yoriwake.gradle.report

import io.github.zeuspizza.yoriwake.agent.select.MapReader
import io.github.zeuspizza.yoriwake.agent.select.Selector
import io.github.zeuspizza.yoriwake.gradle.change.PathIgnore
import java.io.File

// What `pathIgnore` did on a run, as the console and explain.json say it. Only explains: nothing
// here changes the change set or what the test JVM selects.

object PathIgnoreReport {

    /**
     * What the map at [mapDir] says about each path [applied] left out: read when the selector opens
     * a window for it in some JVM; otherwise not read when every JVM that ran a test recorded its
     * reads, and no read evidence when any did not.
     */
    fun evidence(applied: PathIgnore.Applied, mapDir: File): Map<String, PathIgnore.Evidence> {
        if (applied.ignored.isEmpty()) return emptyMap()
        val map = runCatching { MapReader.read(mapDir) }.getOrNull()
        val reads = map?.takeIf { it.isUsable }?.resourceReads()
        val everyJvmRecorded = map != null && reads != null && map.entries().all { entry ->
            map.positionsOf(entry.testId()).all { reads.recorded(it.jvm()) }
        }
        return applied.ignored.associate { (path, _) ->
            path to when {
                map != null && map.isUsable && Selector.ignoredPathWindows(map, listOf(path)).isNotEmpty() ->
                    PathIgnore.Evidence.READ
                everyJvmRecorded -> PathIgnore.Evidence.NOT_READ
                else -> PathIgnore.Evidence.NO_READ_EVIDENCE
            }
        }
    }

    /** The console's lines for one task, none when the build declares nothing that matched. */
    fun lines(applied: PathIgnore.Applied, mapDir: File): List<String> = buildList {
        if (applied.ignored.isNotEmpty()) {
            val perEntry = applied.ignored.groupingBy { it.entry }.eachCount().entries
                .joinToString(", ") { (entry, count) -> "$count by '$entry'" }
            add(
                "pathIgnore left out ${applied.ignored.size} changed paths ($perEntry): " +
                    named(applied.ignored.map { it.path })
            )
            val evidence = evidence(applied, mapDir)
            applied.ignored.filter { evidence[it.path] == PathIgnore.Evidence.READ }.take(NAMED).forEach { (path, entry) ->
                add(
                    "$path was read at capture, or by a test JVM whose reads were not all seen, despite " +
                        "pathIgnore entry '$entry': the tests from that read on in each such JVM run"
                )
            }
            if (PathIgnore.Evidence.NO_READ_EVIDENCE in evidence.values) {
                add(
                    "the map does not record which files every test JVM read, so nothing can show that " +
                        "the paths pathIgnore left out are unread; they are left out as declared"
                )
            }
        }
        applied.notIgnored.take(NAMED).forEach { (path, entry, protection) ->
            add("pathIgnore entry '$entry' matches $path, which stays in the change set (${protection.token})")
        }
        if (applied.notIgnored.size > NAMED) {
            add("pathIgnore matches ${applied.notIgnored.size - NAMED} more paths that stay in the change set")
        }
    }

    private fun named(paths: List<String>): String =
        paths.take(NAMED).joinToString(", ") + if (paths.size > NAMED) ", ..." else ""

    private const val NAMED = 5
}
