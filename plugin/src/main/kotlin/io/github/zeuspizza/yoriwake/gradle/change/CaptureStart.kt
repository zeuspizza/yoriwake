package io.github.zeuspizza.yoriwake.gradle.change

import org.gradle.api.provider.Property
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import java.io.File

/**
 * HEAD and the reflogs that move with it, read before a capture's build compiles anything and again
 * at its decode. Records describe one commit only if neither moved in between: a checkout and back,
 * or a stash and pop, leaves HEAD equal while the tests saw another tree, but appends to a reflog.
 */
internal object CaptureStart {

    /** Beside the pending snapshots; written by the test task, read by its decode. */
    const val PENDING_FILE = "capture-head.pending"

    private const val ABSENT = "absent"
    private const val NO_GIT = "no-git"

    data class Reading(val head: String?, val headReflog: String, val stashReflog: String) {
        val reflogs: Pair<String, String> get() = headReflog to stashReflog

        fun encode(): String = listOf(head.orEmpty(), headReflog, stashReflog).joinToString("\n")
    }

    fun read(rootDir: File): Reading {
        val git = ChangeDetection.directRunner(rootDir)
        return Reading(
            ChangeDetection.head(git),
            reflogToken(git, rootDir, "logs/HEAD"),
            reflogToken(git, rootDir, "logs/refs/stash"),
        )
    }

    /** Null for anything [Reading.encode] did not write. */
    fun decode(text: String?): Reading? {
        val lines = text?.split("\n") ?: return null
        if (lines.size != 3 || lines[1].isEmpty() || lines[2].isEmpty()) return null
        return Reading(lines[0].takeIf(String::isNotEmpty), lines[1], lines[2])
    }

    /**
     * The reflog's stat token, at the path git names, so a linked worktree reads its own. A token
     * that could not be read is unique, so it never compares equal: an unreadable reflog discards.
     */
    private fun reflogToken(git: ChangeDetection.Runner, rootDir: File, gitPath: String): String {
        // No repository, or git failing: equal at both ends only when it failed at both, and a
        // capture outside a repository has no stamp to protect.
        val path = git.run(listOf("rev-parse", "--git-path", gitPath))?.firstOrNull() ?: return NO_GIT
        val unreadable = "unreadable-${System.nanoTime()}"
        val file = File(path).let { if (it.isAbsolute) it else File(rootDir, path) }
        if (!file.exists()) return ABSENT
        return WorkingTree.statToken(file.toPath()) ?: unreadable
    }

    /**
     * [read] at configuration time. A value source, so a reusing build re-obtains it before any
     * task runs, compilation included, and reconfigures when it moved.
     */
    internal abstract class Source : ValueSource<String, Source.Parameters> {

        interface Parameters : ValueSourceParameters {
            val rootDir: Property<String>
        }

        override fun obtain(): String = read(File(parameters.rootDir.get())).encode()
    }
}
