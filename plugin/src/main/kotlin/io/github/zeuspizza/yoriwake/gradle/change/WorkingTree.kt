package io.github.zeuspizza.yoriwake.gradle.change

import io.github.zeuspizza.yoriwake.agent.contract.AgentContract
import io.github.zeuspizza.yoriwake.gradle.YoriwakePlugin
import io.github.zeuspizza.yoriwake.gradle.capture.CoverageDecoder
import io.github.zeuspizza.yoriwake.gradle.capture.writeAtomically
import io.github.zeuspizza.yoriwake.gradle.facts.BuildMemo
import org.gradle.api.logging.Logging
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
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
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

    /**
     * Every tracked path's stat token where a capture starts, once per build for every map. Beside
     * the maps' directory, not in it, which holds only maps.
     */
    const val STATS_PENDING_FILE = "yoriwake-capture-stats.pending"

    private const val START_ID = "yoriwake-start"

    private const val HEADER = "yoriwake-worktree-snapshot 1"
    private const val STATS_HEADER = "yoriwake-capture-stats 1"
    private const val TOKEN_ABSENT = "-"
    private const val TOKEN_UNREADABLE = "?"
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
    ): List<String>? = askGit(excluded, git)?.let { filter(rootDir, excluded, it) }

    /** git's two answers [listing] is filtered from, NUL-separated as git printed them. */
    private class GitListing(val tracked: String, val untracked: String)

    private fun askGit(excluded: Collection<String>, git: (List<String>) -> String?): GitListing? {
        val tracked = git(listOf("diff", "--name-only", "--no-renames", "--relative", "-z", "HEAD"))
            ?: return null
        // The pathspecs only spare git the walk; the filter below is what decides.
        val untracked = git(
            listOf("ls-files", "-z", "--others", "--", ".", ":(exclude,glob)**/.gradle/**") +
                excluded.map { ":(exclude,literal)$it" }
        ) ?: return null
        return GitListing(tracked, untracked)
    }

    private fun filter(rootDir: File, excluded: Collection<String>, said: GitListing): List<String>? {
        val isGradleBuild = gradleBuildAt(rootDir)
        val excludedSet = excluded.toHashSet()
        val paths = (said.tracked.split('\u0000') +
            said.untracked.split('\u0000').filterNot { isExcluded(it, excludedSet, isGradleBuild) })
            .filter(String::isNotEmpty)
            .distinct()
        // A non-UTF-8 name decodes to U+FFFD and would read as a file that is always absent.
        return if (paths.any { '�' in it }) null else paths
    }

    /**
     * [listing] while configuring, NUL-joined, and null when git could not list the tree. Every
     * `Test` task of a build asks with the same root, excluded directories and memoised git
     * answers, so the build filters once instead of once per task.
     */
    fun configuredListing(
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

    /** The tree a capture's tests will see, from paths already [listing]ed, as a dated snapshot. */
    fun startSnapshot(rootDir: File, listed: List<String>, previousText: String?): String {
        // Taken BEFORE anything is observed, so a file touched during the walk is racy, not trusted.
        val now = System.currentTimeMillis()
        val previous = previousText?.let(::parse)
        return render(Snapshot(now, listed.associateWith { observe(rootDir, it, previous) }))
    }

    /**
     * The stat token of every path HEAD pins, beside the directories left out of the listing, so the
     * end of a capture can see a tracked file that was rewritten and restored during it.
     */
    fun startStats(rootDir: File, tracked: List<String>, excluded: Collection<String>): String {
        val isBuildState = buildState(rootDir, excluded)
        val kept = tracked.filterNot(isBuildState)
        return buildString {
            append(STATS_HEADER).append('\t').append(escape(excluded.joinToString("\u0000"))).append('\n')
            kept.forEach { path -> append(tokenOf(rootDir, path)).append('\t').append(escape(path)).append('\n') }
            append(FOOTER).append('\t').append(kept.size).append('\n')
        }
    }

    private fun parseStats(text: String): Pair<List<String>, Map<String, String>>? = runCatching {
        val lines = text.split('\n').dropLastWhile(String::isEmpty)
        val header = lines.first().split('\t')
        require(header.size == 2 && header[0] == STATS_HEADER)
        val footer = lines.last().split('\t')
        require(footer.size == 2 && footer[0] == FOOTER)
        val body = lines.subList(1, lines.size - 1)
        require(body.size == footer[1].toInt())
        val excluded = unescape(header[1]).split('\u0000').filter(String::isNotEmpty)
        excluded to body.associate { line ->
            val (token, path) = line.split('\t').also { require(it.size == 2) }
            unescape(path) to token
        }
    }.getOrNull()

    private fun tokenOf(rootDir: File, path: String): String =
        when (val stat = stat(File(rootDir, path).toPath())) {
            null -> TOKEN_ABSENT
            UNREADABLE -> TOKEN_UNREADABLE
            else -> stat.first
        }

    /** The snapshot a dating capture writes once its tests have run; see [reobserve]. */
    class Reobserved(val undated: String)

    /**
     * The tree at the end of a capture, against [startText] and [statsText] read where it started:
     * every path whose stat token or content moved in between is written unknown, even when its
     * content returned, so later selecting runs keep it in their change set until the next capture.
     * Null when either start file is missing or unreadable, or the tree cannot be listed: the
     * snapshot is then removed, and the next run refuses and captures.
     */
    fun reobserve(
        rootDir: File,
        startText: String?,
        statsText: String?,
        git: (List<String>) -> String? = { ChangeDetection.rawGit(rootDir, it) },
    ): Reobserved? {
        val start = startText?.let(::parse) ?: return null
        val (excluded, stats) = statsText?.let(::parseStats) ?: return null
        val listed = listing(rootDir, excluded, git) ?: return null
        val now = System.currentTimeMillis()
        val entries = HashMap<String, State>()
        (start.entries.keys + listed).forEach { path ->
            val recorded = start.entries[path]
            val current = observe(rootDir, path, start)
            entries[path] = if (recorded != null && untouched(recorded, current)) current else Unknown
        }
        stats.forEach { (path, token) ->
            if (path !in entries && touched(token, tokenOf(rootDir, path))) entries[path] = Unknown
        }
        return Reobserved(render(Snapshot(now, entries)))
    }

    /** As [same], and for a file present at both ends its stat token too: touched counts, not only changed. */
    private fun untouched(a: State, b: State): Boolean = when {
        a is Present && b is Present -> !touched(a.stat, b.stat) && a.sha == b.sha
        else -> same(a, b)
    }

    /**
     * Whether a path was touched between two stat tokens: a write moves its mtime and ctime even when
     * the content returned. The one comparison for paths the snapshot lists and paths HEAD pins.
     */
    private fun touched(startToken: String, nowToken: String) = startToken != nowToken

    /**
     * Which start file this daemon last wrote at each path, by the id written into it. A daemon runs
     * one build at a time, so the test task reads here the id its own configuration wrote, and the
     * decode reads a start file only if it still carries that id: another build configuring the
     * same project meanwhile rewrites the file, and the capture is then not dated from it.
     */
    private val startIds = ConcurrentHashMap<String, String>()

    /** The id [file] was last written with by this daemon, or null. */
    fun startId(file: File): String? = startIds[file.path]

    private fun writeStart(file: File, text: String) {
        val id = UUID.randomUUID().toString()
        startIds.remove(file.path)
        file.parentFile.mkdirs()
        writeAtomically(file, "$START_ID\t$id\n$text")
        startIds[file.path] = id
    }

    /** [file]'s content if it was written with [id], else null. */
    fun readStart(file: File, id: String?): String? {
        id ?: return null
        val text = file.takeIf(File::isFile)?.let { runCatching { it.readText() }.getOrNull() } ?: return null
        val header = "$START_ID\t$id\n"
        return if (text.startsWith(header)) text.removePrefix(header) else null
    }

    /**
     * Writes a capture's start reading of the tree beside the map at configuration time, before the
     * build compiles anything: [startSnapshot]. A reusing build re-obtains it before any task runs,
     * which is the point; its value is constant, so the files a build writes into the tree never cost
     * it its stored entry. The paths are a parameter, listed once per build by the caller through git
     * answers the configuration cache already re-checks.
     */
    internal abstract class StartSource : ValueSource<String, StartSource.Parameters> {

        interface Parameters : ValueSourceParameters {
            val rootDir: Property<String>
            val mapDir: Property<String>
            /** Where the snapshot is written. */
            val file: Property<String>
            /** NUL-joined; unset when git could not list the tree. */
            val listed: Property<String>
        }

        override fun obtain(): String {
            val file = File(parameters.file.get())
            // Gone unless written below: a missing file makes the decode drop the snapshot.
            startIds.remove(file.path)
            file.delete()
            val listed = parameters.listed.orNull?.split('\u0000')?.filter(String::isNotEmpty) ?: return WRITTEN
            runCatching {
                val mapDir = File(parameters.mapDir.get())
                val previous = File(mapDir, SNAPSHOT_FILE).takeIf(File::isFile)?.let { runCatching { it.readText() }.getOrNull() }
                writeStart(file, startSnapshot(File(parameters.rootDir.get()), listed, previous))
            }.onFailure { logger.warn("[yoriwake] could not read the working tree where a capture starts ($it); its map will not be dated") }
            return WRITTEN
        }
    }

    /** [startStats] at configuration time, once per build for all its maps; as [StartSource]. */
    internal abstract class StatsSource : ValueSource<String, StatsSource.Parameters> {

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
            startIds.remove(file.path)
            file.delete()
            val split = { joined: String? -> joined?.split('\u0000')?.filter(String::isNotEmpty) }
            val tracked = split(parameters.tracked.orNull) ?: return WRITTEN
            runCatching {
                writeStart(file, startStats(File(parameters.rootDir.get()), tracked, split(parameters.excluded.orNull).orEmpty()))
            }.onFailure { logger.warn("[yoriwake] could not read the tracked files where a capture starts ($it); its map will not be dated") }
            return WRITTEN
        }
    }

    private const val WRITTEN = "written"

    private val logger = Logging.getLogger(WorkingTree::class.java)

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

    /** [stat]'s token for one file, null when it is not a regular file or cannot be read. */
    internal fun statToken(file: Path): String? = stat(file)?.takeIf { it !== UNREADABLE }?.first

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
