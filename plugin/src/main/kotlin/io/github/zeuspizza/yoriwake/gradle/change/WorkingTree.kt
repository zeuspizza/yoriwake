package io.github.zeuspizza.yoriwake.gradle.change

import io.github.zeuspizza.yoriwake.agent.contract.AgentContract
import io.github.zeuspizza.yoriwake.gradle.capture.CoverageDecoder
import org.gradle.api.provider.Property
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import java.io.File
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * What the working tree held beyond what HEAD pins down when the map was captured, and which of it
 * has moved since.
 *
 * A capture commit says nothing about uncommitted, untracked or ignored files, so `git diff` from it
 * cannot see a reverted dirty edit or an edited ignored fixture. A capture hashes every path HEAD
 * does not pin; a selecting run adds every such path whose content moved or that the snapshot never
 * saw. It only ever adds paths. Hashes, not presence, because builds regenerate ignored files.
 *
 * Paths are relative to the Gradle root, like [ChangeDetection.changedPaths].
 */
internal object WorkingTree {

    /** Beside `capture-commit`, and written under the same gate: see [CoverageDecoder.decode]. */
    const val SNAPSHOT_FILE = "worktree-snapshot"

    private const val HEADER = "yoriwake-worktree-snapshot 1"
    private const val FOOTER = "end"

    /**
     * How close to the snapshot a file's mtime may be before its stat is not trusted. git calls such
     * an entry "racily clean": an edit in the same timestamp tick as the hash, of the same size,
     * leaves the stat unchanged. Two seconds covers FAT's granularity and a little clock skew.
     */
    private const val RACY_MILLIS = 2_000L

    sealed interface State
    data class Present(val stat: String, val mtimeMillis: Long, val sha: String) : State
    object Absent : State
    /** Could not be read as one regular file. Never equal to anything, so it always reports. */
    object Unknown : State

    class Snapshot(val takenMillis: Long, val entries: Map<String, State>)

    sealed interface Drift {
        /** Paths to add to the change set; empty means nothing moved. */
        data class Moved(val paths: Set<String>) : Drift
        /** git could not list the tree: no change set, exactly as a failed `git diff`. */
        object Unlisted : Drift
        /** The map exists and what it saw of the tree is unknown, so the run refuses. */
        data class Unknown(val kind: RefusalKind, val reason: String) : Drift
    }

    /**
     * Build output and Gradle's own state, as paths relative to [rootDir]: rewritten by every build
     * and never read by tests by path.
     */
    fun excluded(rootDir: File, buildDirs: Collection<String>): List<String> =
        buildDirs.mapNotNull { dir ->
            File(dir).relativeToOrNull(rootDir)?.invariantSeparatorsPath?.trim('/')
                // Empty would exclude the whole tree; `..` is outside what the listing sees.
                ?.takeIf { it.isNotEmpty() && it != ".." && !it.startsWith("../") }
        }.distinct().sorted()

    private fun isExcluded(
        path: String,
        excluded: Set<String>,
        isGradleBuild: (String) -> Boolean,
    ): Boolean {
        val directories = path.split('/').dropLast(1)
        // `.kotlin` is the Kotlin Gradle plugin's state beside `.gradle`: a marker per live
        // compiler session, so every run that starts a new daemon would otherwise force.
        return ".gradle" in directories || ".kotlin" in directories || ".git" in directories ||
            isUnderExcluded(path, excluded) ||
            directories.indices.any { i ->
                directories[i] == "build" && isGradleBuild(directories.subList(0, i).joinToString("/"))
            }
    }

    /**
     * Whether [path] is one of [excluded] or below one: the path itself and each prefix ending at
     * a `/`, looked up. Its cost follows the path's depth, not the project count, which on a build
     * with hundreds of projects and a quarter of a million ignored files is minutes.
     */
    private fun isUnderExcluded(path: String, excluded: Set<String>): Boolean {
        if (path in excluded) return true
        var slash = path.indexOf('/')
        while (slash >= 0) {
            if (path.substring(0, slash) in excluded) return true
            slash = path.indexOf('/', slash + 1)
        }
        return false
    }

    /** Whether a path is build output or Gradle's own state, as the listing below leaves out. */
    fun buildState(rootDir: File, excluded: Collection<String>): (String) -> Boolean {
        val isGradleBuild = gradleBuildAt(rootDir)
        val excludedSet = excluded.toHashSet()
        return { path -> isExcluded(path, excludedSet, isGradleBuild) }
    }

    private val BUILD_SCRIPTS =
        listOf("build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts")

    /**
     * Whether [dir], relative to [rootDir], holds a Gradle build script. Its `build/` is then build
     * output even when this build does not configure that project: `buildSrc`, an included build,
     * a TestKit project a test wrote. This build knows none of their build directories, and a test
     * suite that runs builds writes thousands of files there.
     */
    private fun gradleBuildAt(rootDir: File): (String) -> Boolean {
        val seen = HashMap<String, Boolean>()
        return { dir ->
            seen.getOrPut(dir) {
                val at = if (dir.isEmpty()) rootDir else File(rootDir, dir)
                BUILD_SCRIPTS.any { File(at, it).isFile }
            }
        }
    }

    /**
     * Every path HEAD does not pin: tracked files that differ from it (deleted ones included) and
     * every untracked file, ignored or not, except build output. Null when git could not answer.
     *
     * Tracked paths are not excluded: a tracked file is never regenerated noise.
     */
    fun listing(
        rootDir: File,
        excluded: Collection<String>,
        git: (List<String>) -> String? = { ChangeDetection.rawGit(rootDir, it) },
    ): List<String>? {
        val tracked = git(listOf("diff", "--name-only", "--no-renames", "--relative", "-z", "HEAD"))
            ?: return null
        // The pathspecs only spare git the walk; the filter below is what decides.
        val untracked = git(
            listOf("ls-files", "-z", "--others", "--", ".", ":(exclude,glob)**/.gradle/**") +
                excluded.map { ":(exclude,literal)$it" }
        ) ?: return null
        val isGradleBuild = gradleBuildAt(rootDir)
        val excludedSet = excluded.toHashSet()
        val paths = (tracked.split('\u0000') +
            untracked.split('\u0000').filterNot { isExcluded(it, excludedSet, isGradleBuild) })
            .filter(String::isNotEmpty)
            .distinct()
        // A non-UTF-8 name decodes to U+FFFD and would read as a file that is always absent.
        return if (paths.any { '�' in it }) null else paths
    }

    /** The two snapshots a capture may write; which one is decided once the run is over. */
    class Captured(
        /** For a capture that re-observed the whole suite: the tree as it is. */
        val dated: String,
        /**
         * For one that did not: its records span this tree and the previous snapshot's, so every
         * path where the two disagree is written as unknown until a full capture. Null with no
         * previous snapshot to amend.
         */
        val undated: String?,
    )

    /** Null when the tree could not be listed; the snapshot is then removed and the next run refuses. */
    fun capture(
        rootDir: File,
        excluded: Collection<String>,
        previousText: String?,
        git: (List<String>) -> String? = { ChangeDetection.rawGit(rootDir, it) },
    ): Captured? {
        // Taken BEFORE anything is observed, so a file touched during the walk is racy, not trusted.
        val now = System.currentTimeMillis()
        val listed = listing(rootDir, excluded, git) ?: return null
        val previous = previousText?.let(::parse)
        val observed = HashMap<String, State>()
        val state = { path: String -> observed.getOrPut(path) { observe(rootDir, path, previous) } }
        val dated = render(Snapshot(now, listed.associateWith(state)))
        val undated = previous?.let {
            render(Snapshot(now, (it.entries.keys + listed).associateWith { path ->
                val recorded = it.entries[path]
                val current = state(path)
                if (recorded != null && same(recorded, current)) current else Unknown
            }))
        }
        return Captured(dated, undated)
    }

    /**
     * What a selecting run must add to its change set, against the map in [mapDir].
     *
     * A map with no readable snapshot has seen an unknown tree and refuses; the refused run then
     * captures one. No map at all needs nothing, since that run runs everything.
     */
    fun drift(
        rootDir: File,
        mapDir: File,
        excluded: Collection<String>,
        git: (List<String>) -> String? = { ChangeDetection.rawGit(rootDir, it) },
    ): Drift = driftOver(rootDir, mapDir, listing(rootDir, excluded, git))

    /** The same, over a tree already listed; null [listed] is a tree git could not list. */
    fun driftOver(rootDir: File, mapDir: File, listed: List<String>?): Drift {
        if (!File(mapDir, AgentContract.MAP_SCHEMA_VERSION_FILE).isFile) {
            return Drift.Moved(emptySet())
        }
        val file = File(mapDir, SNAPSHOT_FILE)
        val text = if (file.isFile) runCatching { file.readText() }.getOrNull() else null
        if (text == null) {
            return Drift.Unknown(
                RefusalKind.SNAPSHOT_ABSENT,
                "the map carries no working-tree snapshot, so what its records saw of uncommitted " +
                    "and ignored files cannot be established",
            )
        }
        val snapshot = parse(text) ?: return Drift.Unknown(
            RefusalKind.SNAPSHOT_ABSENT,
            "the map's working-tree snapshot cannot be read, so what its records saw of " +
                "uncommitted and ignored files cannot be established",
        )
        listed ?: return Drift.Unlisted
        return Drift.Moved(moved(rootDir, snapshot, listed))
    }

    /** Recorded paths whose state differs now, and listed paths the snapshot never saw. */
    internal fun moved(rootDir: File, snapshot: Snapshot, listed: Collection<String>): Set<String> {
        val moved = sortedSetOf<String>()
        snapshot.entries.forEach { (path, recorded) ->
            if (!same(recorded, observe(rootDir, path, snapshot))) moved += path
        }
        listed.filterTo(moved) { it !in snapshot.entries }
        return moved
    }

    private fun same(a: State, b: State): Boolean = when {
        a is Present && b is Present -> a.sha == b.sha
        a === Absent && b === Absent -> true
        else -> false
    }

    /**
     * One path's state, hashing only when the stat does not vouch for it. The stat is trusted only
     * for an entry the snapshot recorded well before it was taken -- see [RACY_MILLIS].
     */
    internal fun observe(rootDir: File, path: String, previous: Snapshot?): State {
        val file = File(rootDir, path).toPath()
        val before = stat(file) ?: return Absent
        if (before === UNREADABLE) return Unknown
        val recorded = previous?.entries?.get(path) as? Present
        if (recorded != null && recorded.stat == before.first &&
            recorded.mtimeMillis + RACY_MILLIS < previous.takenMillis
        ) {
            return recorded
        }
        val sha = runCatching { sha256(file) }.getOrNull() ?: return Unknown
        // Changed while it was being read: whichever content the hash saw, it is not a state.
        if (stat(file) != before) return Unknown
        return Present(before.first, before.second, sha)
    }

    private val UNREADABLE = Pair("", 0L)

    /** (stat token, mtime in millis), null when absent, [UNREADABLE] for anything but a file. */
    private fun stat(file: Path): Pair<String, Long>? = try {
        // ctime and inode where the platform has them: `cp -p` can restore an mtime and a size,
        // not a ctime. Windows has neither and falls back to size and mtime.
        val unix = runCatching {
            Files.readAttributes(file, "unix:isRegularFile,size,lastModifiedTime,ctime,ino")
        }.getOrNull()
        if (unix != null) {
            if (unix["isRegularFile"] != true) UNREADABLE else {
                val mtime = unix["lastModifiedTime"] as FileTime
                Pair(
                    "${unix["size"]},${mtime.to(TimeUnit.NANOSECONDS)}," +
                        "${(unix["ctime"] as FileTime).to(TimeUnit.NANOSECONDS)},${unix["ino"]}",
                    mtime.toMillis(),
                )
            }
        } else {
            val basic = Files.readAttributes(file, BasicFileAttributes::class.java)
            if (!basic.isRegularFile) UNREADABLE else Pair(
                "${basic.size()},${basic.lastModifiedTime().to(TimeUnit.NANOSECONDS)}",
                basic.lastModifiedTime().toMillis(),
            )
        }
    } catch (_: NoSuchFileException) {
        null
    } catch (_: Exception) {
        UNREADABLE
    }

    private fun sha256(file: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * The footer carries the count, so a file cut short is refused rather than read as a tree with
     * fewer dirty paths.
     */
    internal fun render(snapshot: Snapshot): String = buildString {
        append(HEADER).append('\t').append(snapshot.takenMillis).append('\n')
        snapshot.entries.toSortedMap().forEach { (path, state) ->
            when (state) {
                is Present -> append("F\t").append(state.stat).append('\t').append(state.mtimeMillis)
                    .append('\t').append(state.sha)
                Absent -> append("D")
                Unknown -> append("?")
            }
            append('\t').append(escape(path)).append('\n')
        }
        append(FOOTER).append('\t').append(snapshot.entries.size).append('\n')
    }

    internal fun parse(text: String): Snapshot? = runCatching {
        val lines = text.split('\n').dropLastWhile(String::isEmpty)
        val header = lines.first().split('\t')
        require(header.size == 2 && header[0] == HEADER)
        val footer = lines.last().split('\t')
        require(footer.size == 2 && footer[0] == FOOTER)
        val body = lines.subList(1, lines.size - 1)
        require(body.size == footer[1].toInt())
        val entries = body.associate { line ->
            val fields = line.split('\t')
            when (fields[0]) {
                "F" -> {
                    require(fields.size == 5 && fields[3].length == 64)
                    unescape(fields[4]) to Present(fields[1], fields[2].toLong(), fields[3])
                }
                "D" -> { require(fields.size == 2); unescape(fields[1]) to Absent }
                "?" -> { require(fields.size == 2); unescape(fields[1]) to Unknown }
                else -> error("unknown state ${fields[0]}")
            }
        }
        require(entries.size == body.size)
        Snapshot(header[1].toLong(), entries)
    }.getOrNull()

    private fun escape(path: String): String =
        path.replace("%", "%25").replace("\t", "%09").replace("\n", "%0A").replace("\r", "%0D")

    private fun unescape(path: String): String =
        path.replace("%0D", "\r").replace("%0A", "\n").replace("%09", "\t").replace("%25", "%")

    /**
     * [drift] at configuration time. A value source, so the configuration cache re-obtains it on
     * every reusing build. The listing is a parameter because it is shared by every task.
     */
    internal abstract class DriftSource : ValueSource<String, DriftSource.Parameters> {

        interface Parameters : ValueSourceParameters {
            val rootDir: Property<String>
            val mapDir: Property<String>
            /**
             * NUL-joined, and unset when git could not list the tree. Not a list property: an unset
             * one reads as empty, which would mean nothing moved.
             */
            val listed: Property<String>
        }

        override fun obtain(): String = encode(
            driftOver(
                File(parameters.rootDir.get()), File(parameters.mapDir.get()),
                parameters.listed.orNull?.split('\u0000')?.filter(String::isNotEmpty),
            )
        )
    }

    internal fun encode(drift: Drift): String = when (drift) {
        is Drift.Moved -> (listOf("moved") + drift.paths).joinToString("\u0000")
        Drift.Unlisted -> "unlisted"
        is Drift.Unknown -> listOf("unknown", drift.kind.token, drift.reason).joinToString("\u0000")
    }

    /** Anything this did not write reads as [Drift.Unlisted], which refuses. */
    internal fun decode(encoded: String?): Drift {
        val parts = encoded?.split('\u0000') ?: return Drift.Unlisted
        return when (parts.first()) {
            "moved" -> Drift.Moved(parts.drop(1).toSet())
            "unknown" -> parts.takeIf { it.size == 3 }
                ?.let { (_, kind, reason) -> RefusalKind.fromToken(kind)?.let { Drift.Unknown(it, reason) } }
                ?: Drift.Unlisted
            else -> Drift.Unlisted
        }
    }
}
