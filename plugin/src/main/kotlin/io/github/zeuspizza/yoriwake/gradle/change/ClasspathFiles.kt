package io.github.zeuspizza.yoriwake.gradle.change

import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * The non-class files the build produced onto a test task's runtime classpath, by content.
 *
 * Change detection leaves build output out: a change reaches it through the tracked sources that
 * produce it. That fails for a file generated from git state, a property or the clock, so its
 * content is recorded at each dating capture and compared before a selecting run's tests start.
 * Class files are the class-digest rule's.
 */
internal object ClasspathFiles {

    /** What a file that could not be read digests to: never equal to anything, itself included. */
    const val UNREADABLE = "unreadable"

    sealed interface Walk {
        /** Path to digest for every non-class file of every build-produced entry. */
        data class Found(val digests: Map<String, String>) : Walk

        /** The walk could not finish, so no table can vouch for anything. */
        data class Refused(val reason: String) : Walk
    }

    /**
     * Every non-class file of each classpath entry under [rootDir] (outside its `.gradle`) or under
     * one of [buildDirs]:
     * each file of a directory, each entry of a jar, never a jar whole (an own jar keeps entry
     * timestamps, so its whole-file digest moves on every clean rebuild). Dependency jars from
     * elsewhere are not the build's output. Paths are relative to [rootDir]; a jar entry is
     * `<jar>!/<entry>`.
     */
    fun walk(
        classpath: Collection<File>,
        buildDirs: Collection<String>,
        rootDir: File,
        maxFiles: Int = MAX_FILES,
    ): Walk {
        val roots = buildDirs.map { File(it).absoluteFile } + rootDir.absoluteFile
        // Gradle's and the plugin's own state, such as the agent jar, is not the build's output.
        val state = File(rootDir.absoluteFile, ".gradle").path + File.separator
        val produced = classpath.map(File::getAbsoluteFile).distinct().filter { entry ->
            roots.any { root -> entry.path.startsWith(root.path + File.separator) } && !entry.path.startsWith(state)
        }
        val digests = sortedMapOf<String, String>()
        var files = 0
        for (entry in produced) {
            when {
                entry.isDirectory -> {
                    val stack = ArrayDeque(listOf(entry))
                    while (stack.isNotEmpty()) {
                        val listed = stack.removeLast().listFiles()
                        if (listed == null) {
                            // A directory that cannot be listed moves everything recorded under it.
                            digests[pathOf(rootDir, entry)] = UNREADABLE
                            break
                        }
                        for (file in listed) {
                            if (file.isDirectory) {
                                stack.addLast(file)
                                continue
                            }
                            if (file.name.endsWith(".class")) continue
                            if (++files > maxFiles) return budgetSpent(maxFiles)
                            digests[pathOf(rootDir, file)] =
                                runCatching { file.inputStream().use(::sha256) }.getOrDefault(UNREADABLE)
                        }
                    }
                }
                entry.isFile && (entry.name.endsWith(".jar") || entry.name.endsWith(".zip")) -> {
                    val jar = pathOf(rootDir, entry)
                    val read = runCatching {
                        ZipFile(entry).use { zip ->
                            for (zipped in zip.entries()) {
                                if (zipped.isDirectory || zipped.name.endsWith(".class")) continue
                                if (++files > maxFiles) return budgetSpent(maxFiles)
                                digests["$jar!/${zipped.name}"] =
                                    runCatching { zip.getInputStream(zipped).use(::sha256) }.getOrDefault(UNREADABLE)
                            }
                        }
                    }
                    if (read.isFailure) digests[jar] = UNREADABLE
                }
                entry.isFile -> {
                    if (++files > maxFiles) return budgetSpent(maxFiles)
                    digests[pathOf(rootDir, entry)] =
                        runCatching { entry.inputStream().use(::sha256) }.getOrDefault(UNREADABLE)
                }
                // An entry that does not exist (a resource directory with nothing in it) holds nothing.
            }
        }
        return Walk.Found(digests)
    }

    /** What moved since the capture, each file with how; empty when nothing did. */
    data class Moved(val files: Map<String, String>) {
        val isEmpty: Boolean get() = files.isEmpty()

        /** Up to five files with how each moved, and how many more. */
        fun named(): String {
            val shown = files.entries.take(NAMED).joinToString(", ") { (path, how) -> "$path ($how)" }
            return if (files.size > NAMED) "$shown and ${files.size - NAMED} more" else shown
        }
    }

    /**
     * Every file whose digest differs from [recorded], that appeared, or that is gone. A file that
     * could not be read at either end counts as moved.
     */
    fun compare(recorded: Map<String, String>, current: Map<String, String>): Moved {
        val moved = sortedMapOf<String, String>()
        for ((path, now) in current) {
            val then = recorded[path]
            moved[path] = when {
                then == null -> "appeared"
                now == UNREADABLE || then == UNREADABLE -> "unreadable"
                now != then -> "changed"
                else -> continue
            }
        }
        for (path in recorded.keys) {
            if (path !in current) moved[path] = "gone"
        }
        return Moved(moved)
    }

    private fun budgetSpent(maxFiles: Int) =
        Walk.Refused("the build-produced files on the classpath exceed the $maxFiles-file budget")

    private fun pathOf(rootDir: File, file: File): String =
        runCatching { rootDir.absoluteFile.toPath().relativize(file.absoluteFile.toPath()).toString() }
            .getOrDefault(file.absolutePath)
            .replace(File.separatorChar, '/')

    private fun sha256(input: java.io.InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private const val NAMED = 5

    private const val MAX_FILES = 200_000
}
