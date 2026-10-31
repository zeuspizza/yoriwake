package io.github.zeuspizza.yoriwake.maven

import io.github.zeuspizza.yoriwake.gradle.capture.SelectionRecord
import io.github.zeuspizza.yoriwake.gradle.change.ChangeDetection
import io.github.zeuspizza.yoriwake.gradle.change.TrackedText
import java.io.File

/**
 * A selection record's stamp on Maven: what decides what an execution's tests exercised, read where
 * the execution starts, so a selecting run and its complement read the same fields at the same point.
 *
 * The execution's map id stands for Gradle's task path, the reactor root relative to the git top for
 * its build root, and the module's coordinates for its project path. The configuration digest covers
 * what Surefire hands its tests: their system properties, the host's own argLine (without yoriwake's
 * agent and the run-kind properties) and the include, exclude and group filters.
 */
internal object MavenStamp {

    fun observe(
        /** The invocation's git, run at [root]. */
        git: ChangeDetection.Runner,
        root: File,
        task: String,
        buildPath: String,
        classpath: List<File>,
        localRepository: File,
        systemProperties: Map<String, String>,
        hostArguments: List<String>,
        filters: List<String>,
        /** The execution's one reading of which copies of tracked text files git wrote with `\r\n`. */
        text: TrackedText,
    ): SelectionRecord.Reading {
        val top = git.run(listOf("rev-parse", "--show-toplevel"))?.firstOrNull()
        val buildRoot = top?.let { runCatching { root.canonicalFile.relativeTo(File(it).canonicalFile).invariantSeparatorsPath }.getOrNull() }
        return SelectionRecord.Reading(
            commit = ChangeDetection.head(git),
            clean = ChangeDetection.status(git) { Recording.isBuildState(it) }?.isEmpty() == true,
            task = task,
            // Unknown reads as a root no build has, so it never matches.
            buildRoot = buildRoot ?: "?${root.absolutePath}",
            buildPath = buildPath,
            configuration = SelectionRecord.configurationDigest(
                systemProperties,
                hostArguments.filter { !it.startsWith("-Dyoriwake.") },
                filters.sorted(),
                null,
            ),
        ) { SelectionRecord.classpathDigest(classpath, root, localRepository, text) }
    }
}
