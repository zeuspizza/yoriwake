package io.github.zeuspizza.yoriwake.gradle.change

import io.github.zeuspizza.yoriwake.gradle.Settings
import io.github.zeuspizza.yoriwake.gradle.facts.BuildMemo
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.ProviderFactory
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Turns changed file paths into class prefixes the selector can reason about, and paths it cannot
 * (build scripts, catalogs, resources), which are reported as unmappable rather than ignored.
 */
internal object ChangeDetection {

    // Any number of leading directories, including none: a single-project build has no module
    // segment before `src/`.
    private val SOURCE_ROOT =
        Regex("""^(?:[^/]+/)*?src/[^/]+/(?:kotlin|java)/(?<fqn>.+)\.(?:kt|java)$""")

    /** [inlinableSourceChanged] is false only when every mappable changed path is Java. */
    data class Change(
        val classPrefixes: Set<String>,
        val unmappablePaths: List<String>,
        val inlinableSourceChanged: Boolean = true,
    )

    // Only Kotlin `inline` bodies are copied into callers and erase the edge. Anything not Java
    // counts as inlinable, the conservative direction.
    private val INLINABLE_SOURCE = Regex(""".*\.(?!java$)[A-Za-z0-9_]+$""")

    // An unrecognised path is unmappable, never dropped. Prefixes come from the path and the
    // file's declarations, since a Kotlin file need not be named after its classes.
    fun split(paths: Collection<String>, projectDir: File? = null): Change {
        val prefixes = mutableSetOf<String>()
        val unmappable = mutableListOf<String>()
        var inlinable = false
        paths.map { it.replace('\\', '/') }.filter(String::isNotBlank).forEach { path ->
            val match = SOURCE_ROOT.matchEntire(path)
            if (match == null) {
                unmappable += path
            } else if (declaresNoExecutableCode(projectDir, path)) {
                // Constants are inlined into dependents that are not in the change set, and the
                // holder may never be loaded, so no rule downstream can see the effect.
                unmappable += path
            } else {
                prefixes += match.groups["fqn"]!!.value.replace('/', '.')
                prefixes += declaredTypes(projectDir, path)
                inlinable = inlinable || INLINABLE_SOURCE.matches(path)
            }
        }
        return Change(prefixes, unmappable, inlinable)
    }

    // False when unreadable: such a file already falls back to a path-derived prefix.
    private fun declaresNoExecutableCode(projectDir: File?, path: String): Boolean {
        val file = projectDir?.resolve(path)?.takeIf(File::isFile) ?: return false
        val text = runCatching { file.readText() }.getOrNull() ?: return false

        val rawStrings = rawStringRanges(text)
        fun matchesOutsideSamples(regex: Regex) =
            regex.findAll(text).any { match -> rawStrings.none { match.range.first in it } }

        // A class, object, enum or record always has a constructor, so it is loaded when used.
        // Only a bare interface or annotation can be inlined away entirely.
        if (matchesOutsideSamples(CONSTRUCTED_TYPE)) {
            return false
        }
        return matchesOutsideSamples(TYPE_DECLARATION) && !matchesOutsideSamples(EXECUTABLE_MEMBER)
    }

    private val CONSTRUCTED_TYPE = Regex(
        """(?m)^[^\S
]*(?:(?:public|private|internal|protected|abstract|final|open|sealed|data|value|inner|static|annotation|enum)\s+)*""" +
            """(?:class|object|record|enum)\s+\w+"""
    )

    private fun declaredTypes(projectDir: File?, path: String): Set<String> {
        val file = projectDir?.resolve(path)?.takeIf(File::isFile) ?: return emptySet()
        val text = runCatching { file.readText() }.getOrNull() ?: return emptySet()

        val rawStrings = rawStringRanges(text)
        val packageName = PACKAGE.findAll(text)
            .firstOrNull { match -> rawStrings.none { match.range.first in it } }
            ?.groups?.get("name")?.value?.trim()
        val prefix = if (packageName.isNullOrEmpty()) "" else "$packageName."
        return TYPE_DECLARATION.findAll(text)
            .filterNot { match -> rawStrings.any { match.range.first in it } }
            .mapNotNull { it.groups["name"]?.value }
            .map { prefix + it }
            .toSet()
    }

    // Spans of raw string literals, whose code samples would otherwise yield phantom prefixes.
    // Walked rather than paired, because a `"""` inside a comment would shift the pairing and
    // swallow real declarations. Ambiguity always returns no ranges: an extra prefix forces a full
    // run, a dropped declaration skips a test.
    private fun rawStringRanges(text: String): List<IntRange> {
        val ranges = mutableListOf<IntRange>()
        var index = 0
        var blockCommentDepth = 0
        while (index < text.length) {
            when {
                blockCommentDepth > 0 -> when {
                    // Kotlin nests block comments; Java does not. For a Java `/* /* */` this ends
                    // the file mid-comment, which trips the balance check and suppresses nothing.
                    text.startsWith("/*", index) -> { blockCommentDepth++; index += 2 }
                    text.startsWith("*/", index) -> { blockCommentDepth--; index += 2 }
                    else -> index++
                }
                text.startsWith("//", index) -> {
                    val newline = text.indexOf('\n', index)
                    index = if (newline < 0) text.length else newline
                }
                text.startsWith("/*", index) -> { blockCommentDepth = 1; index += 2 }
                text.startsWith(RAW_STRING_DELIMITER, index) -> {
                    val close = text.indexOf(RAW_STRING_DELIMITER, index + RAW_STRING_DELIMITER.length)
                    if (close < 0) {
                        return emptyList()
                    }
                    // A raw string may end in a quote: the terminator is the last three of a run.
                    var afterClose = close + RAW_STRING_DELIMITER.length
                    while (afterClose < text.length && text[afterClose] == '"') {
                        afterClose++
                    }
                    ranges += index..(afterClose - 1)
                    index = afterClose
                }
                text[index] == '"' -> index = endOfLiteral(text, index, '"')
                text[index] == '\'' -> index = endOfLiteral(text, index, '\'')
                else -> index++
            }
        }
        return if (blockCommentDepth == 0) ranges else emptyList()
    }

    /** The index just past a quoted string or character literal, honouring escapes. */
    private fun endOfLiteral(text: String, start: Int, quote: Char): Int {
        var index = start + 1
        while (index < text.length && text[index] != quote && text[index] != '\n') {
            index += if (text[index] == '\\') 2 else 1
        }
        return if (index < text.length && text[index] == quote) index + 1 else index
    }

    private const val RAW_STRING_DELIMITER = "\"\"\""


    private val PACKAGE = Regex("""^\s*package\s+(?<name>[\w.]+)""", RegexOption.MULTILINE)

    // Deliberately loose: a missed declaration can skip a test, because a name nothing matches
    // is read as "never loaded"; an extra one only forces a full run.
    private val TYPE_DECLARATION = Regex(
        """(?m)^[^\S\n]*(?:@\w[\w.]*(?:\([^)\n]*\))?\s+)*""" +
            """(?:(?:public|private|internal|protected|abstract|final|open|sealed|data|value|inner|static|annotation|enum|fun)\s+)*""" +
            """(?:class|interface|object|record|@interface|enum|typealias)\s+(?<name>\w+)"""
    )

    // javac inlines `static final` primitives and Strings into dependents, so a file of only
    // constants or annotations may never be loaded; absence from the loaded union proves nothing.
    private val EXECUTABLE_MEMBER = Regex(
        """(?m)^[^\S\n]*(?:(?:public|private|internal|protected|abstract|final|open|override|suspend|operator|inline|static|synchronized|native|default)\s+)*""" +
            """(?:fun\s+\w+\s*\(|(?:[\w.<>\[\]?]+\s+)?\w+\s*\([^)]*\)\s*(?:throws [\w, .]+)?\{|init\s*\{|get\(\)|set\()"""
    )

    /** Where a base came from, so the report can say why this run selected what it did. */
    data class Base(val ref: String, val origin: String)

    /**
     * The merge base with the upstream or default branch, used when no base is named. Not `HEAD`,
     * where committed work would yield an empty change set. Null when none resolve.
     */
    fun defaultBase(providers: ProviderFactory, projectDir: File, memo: BuildMemo? = null): Base? =
        // A candidate that does not resolve is the fallback working, not git failing.
        defaultBase(cachedRunner(providers, projectDir, memo, recordFailures = false))

    fun defaultBase(git: Runner): Base? {
        mergeBase(git, "@{upstream}")?.let {
            return Base(it, "merge base with the branch upstream")
        }
        val originHead = git.run(listOf("symbolic-ref", "--short", "refs/remotes/origin/HEAD"))
            ?.firstOrNull()
        if (originHead != null) {
            mergeBase(git, originHead)?.let {
                return Base(it, "merge base with $originHead")
            }
        }
        for (candidate in listOf("origin/main", "origin/master", "main", "master")) {
            mergeBase(git, candidate)?.let {
                return Base(it, "merge base with $candidate")
            }
        }
        return null
    }

    private fun mergeBase(git: Runner, ref: String): String? =
        git.run(listOf("merge-base", "HEAD", ref))?.firstOrNull()?.takeIf(String::isNotBlank)

    /**
     * Paths changed against [against], including uncommitted and untracked work. Null when git
     * cannot answer, never empty: empty means "nothing changed", while null forces a full run.
     */
    fun changedPaths(
        providers: ProviderFactory,
        projectDir: File,
        against: String,
        memo: BuildMemo? = null,
    ): List<String>? = changedPaths(cachedRunner(providers, projectDir, memo), projectDir, against)

    fun changedPaths(git: Runner, projectDir: File, against: String): List<String>? {
        requireSingleCommit(against)
        val tracked = trackedPaths(git, projectDir, against) ?: return null
        val untracked = untrackedPaths(git) ?: return null
        return (tracked + untracked).distinct()
    }

    private fun requireSingleCommit(against: String) {
        // A leading dash would be read as a git option (`--output=<path>` writes files). Refused
        // rather than ignored, so a rejected base never silently becomes HEAD.
        if (against.startsWith("-")) {
            throw IllegalArgumentException(
                "[yoriwake] ${Settings.BASE} must be a git revision, but starts with a dash: " +
                    against
            )
        }
        if (against.contains("..")) {
            throw IllegalArgumentException(
                "[yoriwake] ${Settings.BASE} must name a single commit, not a range: $against. " +
                    "A range makes git compare two commits and ignore uncommitted work, so " +
                    "changes in the working tree would not be selected on."
            )
        }
    }

    /** A commit, by its full sha and its message's first line. */
    data class Commit(val sha: String, val subject: String)

    /** What the commit messages a run selects over say about running everything. */
    sealed interface CommitScan {
        /**
         * [marked] is the newest commit with a `yoriwake: full` line, or null; [hints] are the
         * commits with a `yoriwake:` line that is not one.
         */
        data class Read(val marked: Commit?, val hints: List<Commit>) : CommitScan

        /** git could not list the messages, so whether one asks for a full run is unknown. */
        data class Failed(val reason: String) : CommitScan
    }

    /**
     * Reads the message of every commit in `[since]..HEAD`, and HEAD's own when that range is
     * empty, for a line asking for a full run. One `git log`, parsed here: `--grep`'s regex dialect
     * depends on how git was built.
     */
    fun scanCommitMessages(git: (List<String>) -> String?, since: String): CommitScan {
        requireSingleCommit(since)
        val format = "--format=%H%x00%B%x1e"
        val ranged = git(listOf("log", format, "--end-of-options", "$since..HEAD"))
            ?: return CommitScan.Failed("git log $since..HEAD could not answer")
        val raw = ranged.ifBlank {
            git(listOf("log", "-1", format, "--end-of-options", "HEAD"))
                ?: return CommitScan.Failed("git log HEAD could not answer")
        }
        val commits = raw.split('\u001e').mapNotNull { record ->
            val sha = record.substringBefore('\u0000', "").trim().takeIf(String::isNotEmpty) ?: return@mapNotNull null
            val lines = record.substringAfter('\u0000').lines()
            Commit(sha, lines.firstOrNull { it.isNotBlank() }.orEmpty().trim()) to lines
        }
        val marked = commits.firstOrNull { (_, lines) -> lines.any(::isFullRunMarker) }?.first
        val hints = commits.filter { (_, lines) -> lines.any { HINT.containsMatchIn(it) && !isFullRunMarker(it) } }
            .map { it.first }
        return CommitScan.Read(marked, hints)
    }

    /** A whole line of `yoriwake: full`, in any case, with any spacing around the colon and the line. */
    fun isFullRunMarker(line: String): Boolean = MARKER.matches(line.trim())

    private val MARKER = Regex("""yoriwake\s*:\s*full""", RegexOption.IGNORE_CASE)

    private val HINT = Regex("""^\s*yoriwake\s*:""", RegexOption.IGNORE_CASE)

    /** A local or remote-tracking branch: [shown] as a person writes it, [name] without its remote. */
    data class BranchRef(val shown: String, val name: String)

    /** What is checked out, as far as a branch policy needs to know. */
    sealed interface CheckedOut {
        data class Branch(val name: String) : CheckedOut

        /** [containing] lists every branch whose tip is HEAD or a descendant of it. */
        data class Detached(val containing: List<BranchRef>) : CheckedOut

        /** git could not say, so whether a listed branch is checked out is unknown. */
        data class Failed(val reason: String) : CheckedOut
    }

    /**
     * The checked-out branch or, on a detached HEAD, the branches that contain it. Every command
     * here exits 0 for every ordinary answer, so only git failing reads as [CheckedOut.Failed]. A
     * remote-tracking ref counts only under a remote `git remote` lists, so a pull request's
     * `refs/remotes/pull/<n>/merge` is not a branch. Tags are never read.
     */
    fun checkedOut(git: (List<String>) -> String?): CheckedOut {
        val symbolic = git(listOf("rev-parse", "--symbolic-full-name", "HEAD"))?.trim()
            ?: return CheckedOut.Failed("git rev-parse --symbolic-full-name HEAD could not answer")
        if (symbolic.startsWith(LOCAL)) return CheckedOut.Branch(symbolic.removePrefix(LOCAL))
        if (symbolic != "HEAD") {
            return CheckedOut.Failed("git rev-parse --symbolic-full-name HEAD answered '$symbolic'")
        }
        val refs = git(listOf("for-each-ref", "--contains", "HEAD", "--format=%(refname)", "refs/heads", "refs/remotes"))
            ?: return CheckedOut.Failed("git for-each-ref --contains HEAD could not answer")
        val remotes = git(listOf("remote"))?.lines()?.map(String::trim)?.filter(String::isNotEmpty)
            ?: return CheckedOut.Failed("git remote could not answer")
        val containing = refs.lines().map(String::trim).filter(String::isNotEmpty).flatMap { ref ->
            if (ref.startsWith(LOCAL)) {
                listOf(ref.removePrefix(LOCAL).let { BranchRef(it, it) })
            } else {
                remotes.filter { ref.startsWith("$REMOTE$it/") }.map { remote ->
                    BranchRef(ref.removePrefix(REMOTE), ref.removePrefix("$REMOTE$remote/"))
                }
            }
        }
        return CheckedOut.Detached(containing)
    }

    private const val LOCAL = "refs/heads/"

    private const val REMOTE = "refs/remotes/"

    /** The tracked side of [changedPaths]: what `git diff` from [against] reports. */
    fun trackedPaths(
        providers: ProviderFactory,
        projectDir: File,
        against: String,
        memo: BuildMemo? = null,
    ): List<String>? = trackedPaths(cachedRunner(providers, projectDir, memo), projectDir, against)

    fun trackedPaths(git: Runner, projectDir: File, against: String): List<String>? {
        // --no-renames: with rename detection git reports only the destination, and tests of the
        // class that left the old path are never selected.
        val tracked = git.run(
            listOf("diff", "--name-only", "--no-renames", "--end-of-options", against)
        ) ?: return null
        // Only the diff list is rebased: `git diff --name-only` prints repo-relative paths, while
        // `git ls-files --others` prints cwd-relative ones.
        return rebaseToProject(git, projectDir, tracked)
    }

    /** The untracked side of [changedPaths]. */
    fun untrackedPaths(git: Runner): List<String>? =
        // Ignored files are left out on purpose; `WorkingTree.drift` covers the ones that moved.
        git.run(listOf("ls-files", "--others", "--exclude-standard"))

    // Rebases repo-relative paths onto the Gradle root. Paths outside it stay verbatim, so they
    // remain unmappable and force.
    private fun rebaseToProject(
        git: Runner,
        projectDir: File,
        paths: List<String>,
    ): List<String> {
        val top = git.run(listOf("rev-parse", "--show-toplevel"))
            ?.firstOrNull()?.takeIf(String::isNotBlank) ?: return paths
        val root = runCatching { File(top).canonicalFile }.getOrNull() ?: return paths
        val here = runCatching { projectDir.canonicalFile }.getOrNull() ?: return paths
        if (root == here) {
            return paths
        }
        val prefix = here.relativeToOrNull(root)?.invariantSeparatorsPath?.trim('/')
            ?.takeIf { it.isNotEmpty() } ?: return paths
        return paths.map { path ->
            if (path.startsWith("$prefix/")) path.removePrefix("$prefix/") else path
        }
    }

    /**
     * Every path `git status` reports, untracked files included and ignored ones not: empty for a
     * clean tree, null when git could not answer.
     */
    fun status(git: Runner): List<String>? =
        git.run(listOf("status", "--porcelain", "--untracked-files=all"))

    fun head(providers: ProviderFactory, projectDir: File, memo: BuildMemo? = null): String? =
        head(cachedRunner(providers, projectDir, memo))

    fun head(git: Runner): String? =
        git.run(listOf("rev-parse", "HEAD"))?.firstOrNull()?.takeIf(String::isNotBlank)

    /** The merge base of [a] and [b]; diffing from it reports at least as much as from [a]. */
    fun commonAncestor(
        providers: ProviderFactory,
        projectDir: File,
        a: String,
        b: String,
        memo: BuildMemo? = null,
    ): String? {
        if (a.startsWith("-") || b.startsWith("-")) {
            return null
        }
        return cachedRunner(providers, projectDir, memo)
            .run(listOf("merge-base", "--end-of-options", a, b))
            ?.firstOrNull()?.takeIf(String::isNotBlank)
    }

    /** One git command: via a `ValueSource` at configuration time, directly in a task action. */
    fun interface Runner {
        fun run(arguments: List<String>): List<String>?
    }

    /** Runs git directly in a task action; touches no configuration-time state. */
    fun directRunner(projectDir: File): Runner = Runner { arguments ->
        // Guarded: an InterruptedException on a cancelled build would otherwise fail the host's
        // build from inside the capture finalizer. Could-not-answer forces a full run.
        val output = runCatching { exec(projectDir, arguments) }.getOrDefault(FAILED)
        if (output == FAILED || output == TIMED_OUT) null
        else output.lines().filter(String::isNotBlank)
    }

    /** Runs git at configuration time, through the memoised value source. */
    fun cachedRunner(
        providers: ProviderFactory,
        projectDir: File,
        memo: BuildMemo?,
        recordFailures: Boolean = true,
    ): Runner = Runner { arguments ->
        cachedRawGit(providers, projectDir, memo, arguments, recordFailures)
            ?.lines()?.filter(String::isNotBlank)
    }

    /** git's whole output through the memoised value source: one process per build. */
    internal fun cachedRawGit(
        providers: ProviderFactory,
        projectDir: File,
        memo: BuildMemo?,
        arguments: List<String>,
        recordFailures: Boolean = true,
    ): String? {
        // Keyed by strings: the configuration cache does not preserve `File` reference equality,
        // so a `File` key would miss and spawn a subprocess on every ask.
        val key = (listOf(projectDir.path) + arguments).joinToString("\u0000")
        val create = {
            providers.of(GitOutput::class.java) {
                it.parameters.workingDir.set(projectDir)
                it.parameters.arguments.set(arguments.toList())
            }
        }
        val ask = { (memo?.provider(key, create) ?: create()).get() }
        val output = runCatching {
            memo?.time("git.${arguments.first()}", ask) ?: ask()
        }.getOrNull() ?: return null
        if (output == FAILED || output == TIMED_OUT) {
            if (recordFailures) memo?.recordFailure(
                if (output == TIMED_OUT) {
                    "git ${arguments.first()} did not answer within ${TIMEOUT_SECONDS}s"
                } else {
                    "git ${arguments.joinToString(" ")} could not answer"
                }
            )
            return null
        }
        return output
    }

    /** git's whole output, unsplit, for `-z` listings -- or null when it could not answer. */
    internal fun rawGit(workingDir: File, arguments: List<String>): String? {
        val output = runCatching { exec(workingDir, arguments) }.getOrDefault(FAILED)
        return if (output == FAILED || output == TIMED_OUT) null else output
    }

    /** Sentinel for "git could not answer", never to be confused with "git said nothing". */
    internal const val FAILED = "\u0000yoriwake-git-failed"

    /** Like [FAILED] to every caller, but lets the diagnostic report a hung git separately. */
    internal const val TIMED_OUT = "\u0000yoriwake-git-timed-out"

    internal const val TIMEOUT_SECONDS = 30L

    /** Runs git in a [ValueSource]; `providers.exec` offers no timeout for a hung git. */
    internal abstract class GitOutput : ValueSource<String, GitOutput.Parameters> {

        interface Parameters : ValueSourceParameters {
            val workingDir: DirectoryProperty
            val arguments: ListProperty<String>
        }

        override fun obtain(): String = exec(parameters.workingDir.get().asFile, parameters.arguments.get())
    }

    /** One git invocation, or a sentinel; shared so configuration and execution ask alike. */
    internal fun exec(workingDir: File, arguments: List<String>): String {
        val process = runCatching {
            ProcessBuilder(listOf("git") + arguments)
                .directory(workingDir)
                // stderr is discarded, not merged: git's advisory warnings (one CRLF notice per
                // file on Windows) would otherwise read as changed paths.
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
        }.getOrNull() ?: return FAILED

        // Read on another thread, so a git that blocks mid-output still hits the timeout. Read
        // whole, not by line: `-z` listings are NUL-separated and a path may contain '\r'.
        val output = StringBuilder()
        val reader = Thread {
            runCatching {
                output.append(process.inputStream.bufferedReader().readText())
            }
        }
        reader.isDaemon = true
        reader.start()

        if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            reader.join(1_000)
            return TIMED_OUT
        }
        reader.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS))
        return if (process.exitValue() != 0) FAILED else output.toString()
    }
}
