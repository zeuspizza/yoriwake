package io.github.zeuspizza.yoriwake.gradle.capture

import io.github.zeuspizza.yoriwake.agent.contract.AgentContract
import io.github.zeuspizza.yoriwake.agent.contract.Tsv
import io.github.zeuspizza.yoriwake.agent.select.SelectionRecordFile
import io.github.zeuspizza.yoriwake.gradle.change.ChangeDetection
import io.github.zeuspizza.yoriwake.gradle.change.ClasspathFiles
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * `selection.tsv`: the tests a selecting run that narrowed ran to an outcome, stamped with what
 * decided what they exercised. A complement run leaves exactly those tests out, so every doubt about
 * a run's record removes it instead of writing it: a missing record only costs a full run.
 *
 * The stamp is read at the run's start ([Start]) and checked again at its decode: the commit and a
 * clean tree at both ends, then the task, the build, the classpath and the task's configuration. The
 * test JVM adds its own identity to each decision record, and the records must agree.
 */
internal object SelectionRecord {

    /** The stamp read where the run starts, with the run's token; the decode consumes it. */
    const val START_FILE = "selection-start.pending"

    /** What decides what a run's tests exercised, as the daemon sees it. Compared field by field. */
    data class Stamp(
        /** HEAD, or null when git could not say. */
        val commit: String?,
        /** Whether `git status` reported nothing, untracked files included. */
        val clean: Boolean,
        val task: String,
        /** The build's root directory relative to the repository's top level; empty at the top. */
        val buildRoot: String,
        /** The project's path in the build tree, which differs for an included build. */
        val buildPath: String,
        val classpath: String,
        val configuration: String,
    )

    /**
     * The first field in which a run whose stamp is [now] differs from a [recorded] one, as a reader
     * names it, or null when it differs in none. A tree that is not clean now never matches.
     */
    fun mismatch(recorded: Stamp, now: Stamp): String? = when {
        now.commit == null || now.commit != recorded.commit ->
            "the commit: the record is at ${recorded.commit ?: "an unknown commit"}, this run at ${now.commit ?: "an unknown commit"}"
        !now.clean -> "the working tree, which is not clean"
        now.task != recorded.task -> "the task: ${recorded.task} in the record, ${now.task} here"
        now.buildRoot != recorded.buildRoot || now.buildPath != recorded.buildPath ->
            "the build: ${describe(recorded)} in the record, ${describe(now)} here"
        now.classpath != recorded.classpath -> "the test runtime classpath" + (
            unmatchedReason(now.classpath)?.let { ", whose build output this run could not digest: $it" }
                ?: unmatchedReason(recorded.classpath)?.let { ", whose build output the selecting run could not digest: $it" }
                ?: ""
            )
        now.configuration != recorded.configuration ->
            "the task's configuration (its system properties, JVM arguments or test filters)"
        else -> null
    }

    private fun describe(stamp: Stamp) = "${stamp.buildRoot.ifEmpty { "the repository's top level" }} (${stamp.buildPath})"

    /** What a selecting run's first action leaves its decode. */
    class Start(val token: String, val stamp: Stamp) {
        fun encode(): String = listOf(
            token, stamp.commit.orEmpty(), stamp.clean.toString(), stamp.task, stamp.buildRoot,
            stamp.buildPath, stamp.classpath, stamp.configuration,
        ).joinToString("\n") { Tsv.escape(it) }

        companion object {
            /** Null for anything [encode] did not write. */
            fun decode(text: String?): Start? {
                val fields = text?.split("\n")?.map(Tsv::unescape) ?: return null
                if (fields.size != 8 || fields[0].isEmpty() || fields[2] !in setOf("true", "false")) return null
                return Start(
                    fields[0],
                    Stamp(fields[1].ifEmpty { null }, fields[2].toBoolean(), fields[3], fields[4], fields[5], fields[6], fields[7]),
                )
            }
        }
    }

    sealed interface Outcome {
        data class Written(val tests: Int) : Outcome

        data class Removed(val reason: String) : Outcome
    }

    fun writeStart(mapDir: File, start: Start) {
        mapDir.mkdirs()
        writeAtomically(File(mapDir, START_FILE), start.encode())
    }

    /**
     * After a run that selected: writes the record from this run's decision records, or removes any
     * record when one of them is not this run's, was cut short, or disagrees on the test JVM, when the
     * run did not narrow, or when HEAD or the tree moved or was not clean. Consumes the start reading.
     */
    fun afterSelectingRun(mapDir: File, rootDir: File, ranEverything: Boolean): Outcome {
        val start = File(mapDir, START_FILE).let { file ->
            Start.decode(file.takeIf(File::isFile)?.let { runCatching { it.readText() }.getOrNull() })
                .also { runCatching { file.delete() } }
        } ?: return remove(mapDir, "the run's start was not recorded")
        if (ranEverything) return remove(mapDir, "the run ran every test")
        val merged = when (val merge = merge(mapDir, start.token)) {
            is Merge.Refused -> return remove(mapDir, merge.reason)
            is Merge.Merged -> merge
        }
        if (!merged.narrowed) return remove(mapDir, "the run did not narrow")
        val git = ChangeDetection.directRunner(rootDir)
        val head = ChangeDetection.head(git)
        val status = ChangeDetection.status(git)
        when {
            start.stamp.commit == null -> return remove(mapDir, "HEAD could not be read when the run started")
            head != start.stamp.commit -> return remove(mapDir, "HEAD moved during the run")
            !start.stamp.clean -> return remove(mapDir, "the working tree was not clean when the run started")
            status == null -> return remove(mapDir, "git could not say whether the working tree is clean")
            status.isNotEmpty() -> return remove(mapDir, "the working tree was not clean when the run ended (${status.size} paths)")
        }
        val written = runCatching { write(mapDir, start.stamp, merged.identity, merged.ran) }
        written.exceptionOrNull()?.let { return remove(mapDir, "it could not be written ($it)") }
        return Outcome.Written(merged.ran.size)
    }

    /** After a run asked to select that declined: it ran everything, so no record describes it. */
    fun afterDeclinedRun(mapDir: File): Outcome = remove(mapDir, "the run declined to select")

    fun read(text: String?): SelectionRecordFile.Result = SelectionRecordFile.read(text)

    /** What a complement run's test JVMs noted: the tests they left out and ran, or why one ran all. */
    class Complemented(val leftOut: Int, val ran: Int, val mismatch: String?) {
        /** The decode's one line about the run. */
        fun line(taskPath: String, commit: String?): String {
            val untouched = "nothing was captured, and the map and ${AgentContract.SELECTION_FILE} are as they were"
            return if (mismatch != null) {
                "[yoriwake] $taskPath: every test ran, because $mismatch; $untouched."
            } else {
                "[yoriwake] $taskPath complemented the selecting run at ${commit?.take(12) ?: "its commit"}: " +
                    "$leftOut tests left out as already run, $ran run; $untouched."
            }
        }
    }

    fun complemented(mapDir: File): Complemented {
        var leftOut = 0
        var ran = 0
        var mismatch: String? = null
        decisionParts(mapDir).forEach { part ->
            val notes = runCatching { part.readLines() }.getOrDefault(emptyList())
                .filter { it.startsWith(AgentContract.NOTE_PREFIX) }
                .map { Tsv.split(it.removePrefix(AgentContract.NOTE_PREFIX)) }
                .associate { it[0] to it.drop(1) }
            notes[AgentContract.COMPLEMENT_NOTE]?.let { counts ->
                leftOut += counts.getOrNull(0)?.toIntOrNull() ?: 0
                ran += counts.getOrNull(1)?.toIntOrNull() ?: 0
            }
            if (notes[AgentContract.REFUSAL_KIND_NOTE]?.firstOrNull() == AgentContract.COMPLEMENT_RECORD_MISMATCH_KIND) {
                mismatch = notes[AgentContract.FULL_RUN_REASON_NOTE]?.firstOrNull() ?: "the selection record did not match"
            }
        }
        return Complemented(leftOut, ran, mismatch)
    }

    private fun decisionParts(mapDir: File): List<File> = mapDir.listFiles { file ->
        file.name.startsWith("${AgentContract.DECISIONS_FILE}.") && file.name.endsWith(AgentContract.DECISIONS_PART_SUFFIX)
    }.orEmpty().sortedBy(File::getName)

    /** The daemon's stamp of a usable record. */
    fun stampOf(record: SelectionRecordFile.Result): Stamp {
        val notes = record.notes()
        return Stamp(
            notes[AgentContract.STAMP_COMMIT_NOTE], clean = true,
            task = notes[AgentContract.STAMP_TASK_NOTE].orEmpty(),
            buildRoot = notes[AgentContract.STAMP_BUILD_ROOT_NOTE].orEmpty(),
            buildPath = notes[AgentContract.STAMP_BUILD_PATH_NOTE].orEmpty(),
            classpath = notes[AgentContract.STAMP_CLASSPATH_NOTE].orEmpty(),
            configuration = notes[AgentContract.STAMP_CONFIGURATION_NOTE].orEmpty(),
        )
    }

    /** The test JVM's identity a usable record was written under. */
    fun identityOf(record: SelectionRecordFile.Result): Map<String, String> =
        record.notes().filterKeys { it.startsWith(AgentContract.JVM_NOTE_PREFIX) }
            .mapKeys { it.key.removePrefix(AgentContract.JVM_NOTE_PREFIX) }

    /**
     * A digest of [files] in order, each named relative to [rootDir] or [gradleUserHome] when under
     * one, so the same resolution on another machine digests alike.
     *
     * A file or directory outside [rootDir] is digested by its content too, unless it is in Gradle's
     * module cache, whose paths name their content: a snapshot in a local repository or an included
     * build elsewhere can change at the same path between two runs at one commit. What is under
     * [rootDir] is digested by the content of each file of a directory and each entry of a jar,
     * never a jar whole: the commit and a clean tree do not fix a file generated from git state, a
     * property or the clock, and a jar the build rebuilds need not come out byte for byte alike.
     * When that walk cannot vouch for every file, or the root itself is on the classpath, the digest
     * is one no other run produces, carrying why.
     */
    fun classpathDigest(files: Iterable<File>, rootDir: File, gradleUserHome: File): String {
        val built = mutableListOf<File>()
        val named = files.map { file ->
            // The walk reads only what is beneath the root, so the root itself would be named only.
            if (file.absoluteFile == rootDir.absoluteFile) return unmatched("the build's root directory is on it")
            file.relativeToOrNull(rootDir)?.takeUnless { it.path.startsWith("..") }?.invariantSeparatorsPath?.let {
                built += file
                return@map "root:$it"
            }
            val inHome = file.relativeToOrNull(gradleUserHome)?.takeUnless { it.path.startsWith("..") }?.invariantSeparatorsPath
            if (inHome != null && inHome.startsWith("caches/modules-2/")) return@map "gradle:$inHome"
            (inHome?.let { "gradle:$it" } ?: file.invariantSeparatorsPath) + " " + contentDigest(file)
        }
        val content = when (val walk = runCatching { ClasspathFiles.walk(built, emptyList(), rootDir, classes = true) }
            .getOrElse { ClasspathFiles.Walk.Refused(it.toString()) }) {
            is ClasspathFiles.Walk.Refused -> return unmatched(walk.reason)
            is ClasspathFiles.Walk.Found -> walk.digests
        }
        content.entries.firstOrNull { it.value == ClasspathFiles.UNREADABLE }?.let { return unmatched("${it.key} could not be read") }
        return sha256(named + content.map { (path, digest) -> "built $path $digest" })
    }

    // The reason rides in the digest so that a complement refusing over it can say why.
    private fun unmatched(reason: String) = "$UNMATCHED${UUID.randomUUID()} $reason"

    /** Why [digest] is one no run matches, or null when it is a digest of content. */
    private fun unmatchedReason(digest: String) = digest.takeIf { it.startsWith(UNMATCHED) }?.substringAfter(' ', "")

    private const val UNMATCHED = "unmatched:"

    /** A file's bytes, or a directory's files by relative path and bytes; `absent` when neither. */
    private fun contentDigest(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        fun add(each: File) = each.inputStream().use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        when {
            file.isFile -> add(file)
            file.isDirectory -> file.walkTopDown().filter(File::isFile)
                .map { it.relativeTo(file).invariantSeparatorsPath to it }.sortedBy { it.first }
                .forEach { (path, each) -> digest.update(path.toByteArray(Charsets.UTF_8)); digest.update(0); add(each) }
            else -> return "absent"
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * A digest of what a task's configuration decides about its tests: its system properties and JVM
     * arguments other than the plugin's own, its include and exclude patterns, and its framework's
     * filters, each as text.
     */
    fun configurationDigest(
        systemProperties: Map<String, Any?>,
        jvmArgs: List<String>,
        patterns: List<String>,
        frameworkFilter: String?,
    ): String = sha256(
        systemProperties.filterKeys { !it.startsWith("yoriwake.") }.toSortedMap()
            .map { (key, value) -> "property $key=$value" } +
            jvmArgs.map { "jvmArg $it" } + patterns + listOfNotNull(frameworkFilter?.let { "framework $it" })
    )

    private fun sha256(lines: List<String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        lines.forEach { digest.update(it.toByteArray(Charsets.UTF_8)); digest.update(0) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun remove(mapDir: File, reason: String): Outcome {
        File(mapDir, AgentContract.SELECTION_FILE).delete()
        return Outcome.Removed(reason)
    }

    private sealed interface Merge {
        class Merged(val ran: Map<String, String>, val identity: Map<String, String>, val narrowed: Boolean) : Merge

        class Refused(val reason: String) : Merge
    }

    /**
     * Every decision record this run wrote, with their ran lines. A record without this run's token,
     * cut short, or naming another test JVM identity refuses the whole merge; a test JVM that wrote no
     * record only leaves its tests out.
     */
    private fun merge(mapDir: File, token: String): Merge {
        val parts = decisionParts(mapDir)
        if (parts.isEmpty()) return Merge.Refused("no test JVM wrote a decision record")
        val ran = sortedMapOf<String, String>()
        var identity: Map<String, String>? = null
        var narrowed = false
        var fullRun = false
        for (part in parts) {
            val lines = runCatching { part.readLines() }.getOrNull()
                ?: return Merge.Refused("${part.name} could not be read")
            val notes = mutableMapOf<String, String>()
            var rows = 0
            for (line in lines) {
                when {
                    line.startsWith(AgentContract.NOTE_PREFIX) -> {
                        val fields = Tsv.split(line.removePrefix(AgentContract.NOTE_PREFIX))
                        notes[fields[0]] = fields.getOrElse(1) { "" }
                    }
                    line.startsWith(AgentContract.RAN_LINE_PREFIX) -> {
                        val fields = Tsv.split(line.removePrefix(AgentContract.RAN_LINE_PREFIX))
                        if (fields.size == 2) ran[fields[0]] = fields[1]
                    }
                    line.isNotBlank() && !line.startsWith("#") -> rows++
                }
            }
            if (notes[AgentContract.RUN_TOKEN_NOTE] != token) {
                return Merge.Refused("${part.name} was not written by this run")
            }
            if (notes[AgentContract.ROWS_NOTE] != rows.toString()) {
                return Merge.Refused("${part.name} was cut short")
            }
            val own = notes.filterKeys { it.startsWith(AgentContract.JVM_NOTE_PREFIX) }
                .mapKeys { it.key.removePrefix(AgentContract.JVM_NOTE_PREFIX) }
            if (identity != null && identity != own) {
                return Merge.Refused("the test JVMs ran on different runtimes or platforms")
            }
            identity = own
            when (notes[AgentContract.OUTCOME_NOTE]) {
                AgentContract.RUN_NARROWED -> narrowed = true
                AgentContract.RUN_FULL -> fullRun = true
            }
        }
        return Merge.Merged(ran, identity.orEmpty(), narrowed && !fullRun)
    }

    private fun write(mapDir: File, stamp: Stamp, identity: Map<String, String>, ran: Map<String, String>) {
        val notes = linkedMapOf(
            AgentContract.VERSION_NOTE to AgentContract.SELECTION_VERSION,
            AgentContract.STAMP_COMMIT_NOTE to stamp.commit.orEmpty(),
            AgentContract.STAMP_TASK_NOTE to stamp.task,
            AgentContract.STAMP_BUILD_ROOT_NOTE to stamp.buildRoot,
            AgentContract.STAMP_BUILD_PATH_NOTE to stamp.buildPath,
            AgentContract.STAMP_CLASSPATH_NOTE to stamp.classpath,
            AgentContract.STAMP_CONFIGURATION_NOTE to stamp.configuration,
        )
        identity.forEach { (key, value) -> notes[AgentContract.JVM_NOTE_PREFIX + key] = value }
        notes[AgentContract.ROWS_NOTE] = ran.size.toString()
        val text = buildString {
            append("# test\toutcome\n")
            notes.forEach { (key, value) -> append(AgentContract.NOTE_PREFIX).append(Tsv.join(key, value)).append('\n') }
            ran.forEach { (test, outcome) -> append(Tsv.join(test, outcome)).append('\n') }
        }
        writeAtomically(File(mapDir, AgentContract.SELECTION_FILE), text)
    }
}
