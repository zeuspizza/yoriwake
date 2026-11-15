package io.github.zeuspizza.yoriwake.gradle.report

import io.github.zeuspizza.yoriwake.gradle.change.PathIgnore
import java.io.File

// What `pathIgnore` did on a run, as the console and explain.json say it. Only explains: nothing
// here changes the change set or what the test JVM selects.

internal object PathIgnoreReport {

    /** What the map at [mapDir] says about each path [applied] left out, as [PathIgnore.evidence] reads it. */
    fun evidence(applied: PathIgnore.Applied, mapDir: File): Map<String, PathIgnore.Evidence> =
        PathIgnore.evidence(applied.ignored.map { it.path }, mapDir)

    /** The console's lines for one task, none when the build declares nothing that matched. */
    fun lines(applied: PathIgnore.Applied, mapDir: File): List<String> = buildList {
        if (applied.ignored.isNotEmpty()) {
            val perEntry = applied.ignored.groupingBy { it.label }.eachCount().entries
                .joinToString(", ") { (entry, count) -> "$count by '$entry'" }
            add(
                "pathIgnore left out ${applied.ignored.size} changed paths ($perEntry): " +
                    named(applied.ignored.map { it.path })
            )
            val evidence = evidence(applied, mapDir)
            applied.ignored.filter { evidence[it.path] == PathIgnore.Evidence.READ }.take(NAMED).forEach { ignored ->
                add(
                    "${ignored.path} was read at capture, or by a test JVM whose reads were not all seen, despite " +
                        "pathIgnore entry '${ignored.label}': the tests from that read on in each such JVM run"
                )
            }
            if (PathIgnore.Evidence.NO_READ_EVIDENCE in evidence.values) {
                add(
                    "the map does not record which files every test JVM read, so nothing can show that " +
                        "the paths pathIgnore left out are unread; they are left out as declared"
                )
            }
        }
        applied.notIgnored.take(NAMED).forEach { kept ->
            add("pathIgnore entry '${kept.label}' matches ${kept.path}, which stays in the change set (${kept.protection.token})")
        }
        if (applied.notIgnored.size > NAMED) {
            add("pathIgnore matches ${applied.notIgnored.size - NAMED} more paths that stay in the change set")
        }
    }

    private fun named(paths: List<String>): String =
        paths.take(NAMED).joinToString(", ") + if (paths.size > NAMED) ", ..." else ""

    private const val NAMED = 5
}
