package io.github.zeuspizza.yoriwake.gradle.change

import io.github.zeuspizza.yoriwake.gradle.YoriwakePlugin
import io.github.zeuspizza.yoriwake.gradle.facts.BuildMemo
import org.gradle.api.logging.Logging
import org.gradle.api.provider.Property
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import java.io.File

// The working tree as Gradle reads it at configuration time: once per build, through value sources.

/**
 * [listing] while configuring, NUL-joined, and null when git could not list the tree. Every
 * `Test` task of a build asks with the same root, excluded directories and memoised git
 * answers, so the build filters once instead of once per task.
 */
internal fun WorkingTree.configuredListing(
    rootDir: File,
    excluded: List<String>,
    memo: BuildMemo?,
    git: (List<String>) -> String?,
): String? {
    // Asked outside the memo's compute: git there would hold the memo's lock across a subprocess.
    val said = askGit(excluded, git)
    val filtered = { said?.let { filter(rootDir, excluded, it) }?.joinToString("\u0000") }
    memo ?: return filtered()
    // The root and the excluded directories both: the memo is shared across included builds,
    // and a build handed another's listing would not see its own new files.
    val key = YoriwakePlugin.WORKTREE_KEY + rootDir.path + "\u0000" +
        excluded.joinToString("\u0000")
    return memo.value(key) { memo.time(YoriwakePlugin.WORKTREE_LISTING_COUNTER, filtered) }
}

/**
 * Writes a capture's start reading of the tree beside the maps at configuration time, before the
 * build compiles anything: [WorkingTree.startSnapshot]. Once per build for all its maps, as
 * [WorkingTreeStatsSource]. A reusing build re-obtains it before any task runs, which is the point;
 * its value is constant, so the files a build writes into the tree never cost it its stored entry.
 * The paths are a parameter, listed once per build by the caller through git answers the
 * configuration cache already re-checks.
 */
internal abstract class WorkingTreeStartSource : ValueSource<String, WorkingTreeStartSource.Parameters> {

    interface Parameters : ValueSourceParameters {
        val rootDir: Property<String>
        /** Where the snapshot is written. */
        val file: Property<String>
        /** NUL-joined; unset when git could not list the tree. */
        val listed: Property<String>
    }

    override fun obtain(): String {
        val file = File(parameters.file.get())
        // The last build's reading spares hashing a file whose stat has not moved since. Any
        // reading startSnapshot wrote will do: it trusts a stat only well before its own time.
        // Gone unless written below: a missing file makes the decode drop the snapshot.
        val previous = WorkingTree.takePreviousStart(file)
        val listed = parameters.listed.orNull?.split('\u0000')?.filter(String::isNotEmpty) ?: return WRITTEN
        runCatching {
            WorkingTree.writeStart(file, WorkingTree.startSnapshot(File(parameters.rootDir.get()), listed, previous))
        }.onFailure { logger.warn("[yoriwake] could not read the working tree where a capture starts ($it); its map will not be dated") }
        return WRITTEN
    }
}

/** [WorkingTree.startStats] at configuration time, once per build for all its maps; as [WorkingTreeStartSource]. */
internal abstract class WorkingTreeStatsSource : ValueSource<String, WorkingTreeStatsSource.Parameters> {

    interface Parameters : ValueSourceParameters {
        val rootDir: Property<String>
        /** Where the stats are written. */
        val file: Property<String>
        /** NUL-joined tracked paths; unset when git could not list them. */
        val tracked: Property<String>
        /** NUL-joined. */
        val excluded: Property<String>
    }

    override fun obtain(): String {
        val file = File(parameters.file.get())
        WorkingTree.takePreviousStart(file)
        val split = { joined: String? -> joined?.split('\u0000')?.filter(String::isNotEmpty) }
        val tracked = split(parameters.tracked.orNull) ?: return WRITTEN
        runCatching {
            WorkingTree.writeStart(file, WorkingTree.startStats(File(parameters.rootDir.get()), tracked, split(parameters.excluded.orNull).orEmpty()))
        }.onFailure { logger.warn("[yoriwake] could not read the tracked files where a capture starts ($it); its map will not be dated") }
        return WRITTEN
    }
}

/**
 * [WorkingTree.drift] at configuration time. A value source, so the configuration cache
 * re-obtains it on every reusing build. The listing is a parameter because it is shared by every task.
 */
internal abstract class WorkingTreeDriftSource : ValueSource<String, WorkingTreeDriftSource.Parameters> {

    interface Parameters : ValueSourceParameters {
        val rootDir: Property<String>
        val mapDir: Property<String>
        /**
         * NUL-joined, and unset when git could not list the tree. Not a list property: an unset
         * one reads as empty, which would mean nothing moved.
         */
        val listed: Property<String>
    }

    override fun obtain(): String = WorkingTree.encode(
        WorkingTree.driftOver(
            File(parameters.rootDir.get()), File(parameters.mapDir.get()),
            parameters.listed.orNull?.split('\u0000')?.filter(String::isNotEmpty),
        )
    )
}

private const val WRITTEN = "written"

private val logger = Logging.getLogger(WorkingTree::class.java)
