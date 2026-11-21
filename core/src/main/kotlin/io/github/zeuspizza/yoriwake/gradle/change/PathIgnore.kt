package io.github.zeuspizza.yoriwake.gradle.change

import io.github.zeuspizza.yoriwake.agent.contract.AgentContract
import io.github.zeuspizza.yoriwake.agent.select.MapReader
import io.github.zeuspizza.yoriwake.agent.select.Selector
import java.io.File

/**
 * The build's `pathIgnore`: changed paths its owner declares no test reads, taken out of the change
 * set before anything else sees it. Paths that change what compiles or what a build reads are never
 * taken out, whatever an entry matches, and each match is reported either way, so a wrong
 * declaration can be found.
 *
 * A build also gets [DEFAULTS], unless it turns them off: documentation and CI
 * configuration, which no build reads as a rule. A default entry is held to more than a user's,
 * since nobody vouched for it: see [gated].
 */
internal object PathIgnore {

    /** Matched after the build's own entries. `docs/` is the top-level directory only. */
    val DEFAULTS = listOf(".github/", "docs/", "**/README*", "**/*.md")

    /** How a default entry is spelled where an entry is written as text: the console and the change set. */
    private const val DEFAULT_PREFIX = "default:"

    /** Why a path an entry matches stays in the change set. */
    enum class Protection(val token: String) {
        /** Outside the Gradle root: git prints it verbatim, so it looks like a root-relative path. */
        OUTSIDE_ROOT("outside-root"),

        /**
         * Quoted by git, as it prints a name with bytes it escapes: the string is not the path, so no
         * protection below can be judged on it.
         */
        GIT_QUOTED("git-quoted"),

        /** A build script, a settings or properties file, a version catalog, or build logic. */
        BUILD_FILE("build-file"),

        /** Under a source set's directory, or under any `src` directory. */
        SOURCE_SET("source-set"),

        /** A compiled class of the task holds the path or its file name. */
        NAMED_IN_CLASSES("named-in-classes"),

        /** A default entry, and the map cannot show that no test JVM read the path. */
        NO_READ_EVIDENCE("no-read-evidence"),

        /**
         * A default entry, and the test task declares an input file under the root beyond its
         * classpath and test classes: what a build task derived from the path may reach the test there.
         */
        TASK_INPUT("task-input"),
    }

    /** What the map says about whether a test read an ignored path. */
    enum class Evidence(val token: String) {
        /** A test JVM read it, or every file, at capture: the tests from that point on run. */
        READ("read"),

        /** Every JVM that ran a test recorded its reads, and none read it. */
        NOT_READ("not-read"),

        /** The map records no reads, or some JVM's reads were not recorded. */
        NO_READ_EVIDENCE("no-read-evidence"),
    }

    /** [default] when [entry] is one of [DEFAULTS] rather than the build's own. */
    data class Ignored(val path: String, val entry: String, val default: Boolean = false) {
        /** [entry] as text names it: `default:docs/` for a default. */
        val label: String get() = labelOf(entry, default)
    }

    data class Kept(val path: String, val entry: String, val protection: Protection, val default: Boolean = false) {
        /** [entry] as text names it: `default:docs/` for a default. */
        val label: String get() = labelOf(entry, default)
    }

    private fun labelOf(entry: String, default: Boolean) = if (default) DEFAULT_PREFIX + entry else entry

    /** The change set after the declaration: [kept] goes on, [ignored] is logged, [notIgnored] too. */
    class Applied(val kept: List<String>, val ignored: List<Ignored>, val notIgnored: List<Kept>) {
        companion object {
            fun none(paths: List<String>) = Applied(paths, emptyList(), emptyList())
        }
    }

    /**
     * The entries and what protects a path from them, as plain values a task action may hold.
     * [sourceDirs] and [buildLogicDirs] are root-relative; [outsideRoot] holds the changed paths git
     * reported outside the Gradle root. [defaults] are tried after [entries]. [maven] adds Maven's
     * build files to Gradle's, with [moduleDirs] the reactor's module directories, root-relative: a
     * `pom.xml` in any other directory below the root is another build's.
     */
    class Declaration(
        val entries: List<String>,
        val rootDir: File,
        val sourceDirs: List<String>,
        val buildLogicDirs: List<String>,
        val outsideRoot: Set<String>,
        val defaults: List<String> = emptyList(),
        val maven: Boolean = false,
        val moduleDirs: List<String> = emptyList(),
        /** Files a resource directory that is a whole module's directory takes in, kept as [Protection.SOURCE_SET]. */
        val sourceFiles: Set<String> = emptySet(),
    ) : java.io.Serializable {
        /** Whether [path] matches an entry or a default. */
        fun matches(path: String): Boolean = (entries + defaults).any { PathIgnore.matches(it, path) }
    }

    /**
     * Why [entry] is not an entry this grammar reads, or null when it is. A pattern written for
     * another tool's dialect fails here rather than meaning something else.
     */
    fun problem(entry: String): String? {
        val segments = normalized(entry).split('/')
        return when {
            entry.isBlank() -> "is empty"
            entry.any(Char::isISOControl) -> "holds a control character"
            entry.startsWith("!") -> "starts with `!`, and negation is not supported"
            entry.startsWith("/") -> "starts with `/`, but entries are relative to the root project"
            DRIVE.containsMatchIn(entry) -> "names a drive, but entries are relative to the root project"
            '\\' in entry -> "holds a backslash; separate directories with `/`"
            entry.any { it in "[]{}" } -> "holds `[`, `]`, `{` or `}`, and character classes and braces are not supported"
            segments.any { it == "." || it == ".." } -> "has a `.` or `..` segment"
            segments.any { "**" in it && it != "**" } -> "has `**` inside a segment, where it means only `*`; use it as a whole segment"
            else -> null
        }
    }


    /**
     * Whether [path] matches [entry] whole, case-exact: `*` within one segment, `?` one character
     * within one, `**` as a segment any number of segments, none included, and a trailing `/`
     * everything below that directory.
     */
    fun matches(entry: String, path: String): Boolean =
        matchFrom(normalized(entry).split('/'), 0, path.split('/'), 0)

    private fun normalized(entry: String) = if (entry.endsWith("/")) "$entry**" else entry

    private fun matchFrom(pattern: List<String>, p: Int, segments: List<String>, s: Int): Boolean {
        if (p == pattern.size) return s == segments.size
        if (pattern[p] == "**") return (s..segments.size).any { matchFrom(pattern, p + 1, segments, it) }
        return s < segments.size && segmentMatches(pattern[p], segments[s]) && matchFrom(pattern, p + 1, segments, s + 1)
    }

    private fun segmentMatches(glob: String, segment: String): Boolean {
        if ('*' !in glob && '?' !in glob) return glob == segment
        val regex = buildString {
            var literal = StringBuilder()
            fun flush() { if (literal.isNotEmpty()) append(Regex.escape(literal.toString())); literal = StringBuilder() }
            glob.forEach { c ->
                when (c) {
                    '*' -> { flush(); append(".*") }
                    '?' -> { flush(); append('.') }
                    else -> literal.append(c)
                }
            }
            flush()
        }
        return Regex(regex, RegexOption.DOT_MATCHES_ALL).matches(segment)
    }

    /**
     * Takes every path an entry matches out of [paths], unless it is protected. [namedInClasses]
     * answers which candidates a compiled class of the task names; null or a throw keeps them all.
     */
    fun apply(
        paths: List<String>,
        declaration: Declaration?,
        namedInClasses: (Collection<String>) -> Set<String>?,
    ): Applied {
        if (declaration == null || (declaration.entries.isEmpty() && declaration.defaults.isEmpty())) return Applied.none(paths)
        val matched = paths.distinct().mapNotNull { path ->
            declaration.entries.firstOrNull { matches(it, path) }?.let { path to (it to false) }
                ?: declaration.defaults.firstOrNull { matches(it, path) }?.let { path to (it to true) }
        }.toMap()
        if (matched.isEmpty()) return Applied.none(paths)
        val protectedBy = mutableMapOf<String, Protection>()
        matched.keys.forEach { path -> protection(path, declaration)?.let { protectedBy[path] = it } }
        val candidates = matched.keys - protectedBy.keys
        if (candidates.isNotEmpty()) {
            // A file a test names can be read without the read being seen: handed to a process,
            // or checked for and never opened.
            val named = runCatching { namedInClasses(candidates) }.getOrNull() ?: candidates
            candidates.filter { it in named }.forEach { protectedBy[it] = Protection.NAMED_IN_CLASSES }
        }
        val ignored = matched.filterKeys { it !in protectedBy }
        return Applied(
            paths.filterNot { it in ignored },
            ignored.map { (path, entry) -> Ignored(path, entry.first, entry.second) }.sortedBy { it.path },
            protectedBy.map { (path, why) -> matched.getValue(path).let { Kept(path, it.first, why, it.second) } }.sortedBy { it.path },
        )
    }

    /**
     * [applied] with every path a default entry left out put back when [taskInputs] answers any
     * file (`task-input`), and otherwise when [everyJvmRecorded] does not hold (`no-read-evidence`).
     * A user's entry stands as declared without read evidence; a default has nobody to vouch for it.
     * Neither question is asked when no default left a path out.
     */
    fun gated(
        applied: Applied,
        everyJvmRecorded: () -> Boolean,
        taskInputs: () -> List<String>,
    ): Applied {
        val defaults = applied.ignored.filter { it.default }
        if (defaults.isEmpty()) return applied
        // Not the per-path evidence: a path one JVM read is still unvouched for in a JVM whose
        // reads went unrecorded, and the selector opens no window there, so its tests would skip.
        val putBack: Map<Ignored, Protection> = if (taskInputs().isNotEmpty()) {
            defaults.associateWith { Protection.TASK_INPUT }
        } else if (!everyJvmRecorded()) {
            defaults.associateWith { Protection.NO_READ_EVIDENCE }
        } else {
            emptyMap()
        }
        if (putBack.isEmpty()) return applied
        return Applied(
            applied.kept + putBack.keys.map { it.path },
            applied.ignored - putBack.keys,
            (applied.notIgnored + putBack.map { (it, why) -> Kept(it.path, it.entry, why, default = true) }).sortedBy { it.path },
        )
    }

    /**
     * What the map at [mapDir] says about each of [paths]: read when the selector opens a window for
     * it in some JVM; otherwise not read when every JVM that ran a test recorded its reads, and no
     * read evidence when any did not.
     */
    fun evidence(paths: Collection<String>, mapDir: File): Map<String, Evidence> {
        if (paths.isEmpty()) return emptyMap()
        val map = runCatching { MapReader.read(mapDir) }.getOrNull()
        val everyJvmRecorded = recordedEveryJvm(map)
        return paths.associateWith { path ->
            when {
                map != null && map.isUsable && Selector.ignoredPathWindows(map, listOf(path)).isNotEmpty() -> Evidence.READ
                everyJvmRecorded -> Evidence.NOT_READ
                else -> Evidence.NO_READ_EVIDENCE
            }
        }
    }

    /** Whether the map at [mapDir] recorded the reads of every JVM that ran a test. */
    fun recordsEveryJvm(mapDir: File): Boolean = recordedEveryJvm(runCatching { MapReader.read(mapDir) }.getOrNull())

    private fun recordedEveryJvm(map: MapReader.Result?): Boolean {
        if (map == null || !map.isUsable) return false
        val reads = map.resourceReads() ?: return false
        // A test with no position ran in a JVM nobody can name, so nothing shows its reads recorded.
        // The records that stand for no single test (code run outside any test, a class's static
        // setup) have no position by design; the tests they serve carry their own.
        return map.entries().all { entry ->
            val id = entry.testId()
            val positions = map.positionsOf(id)
            id == AgentContract.UNATTRIBUTED_RECORD_ID || id.startsWith(AgentContract.CLASS_SCOPED_RECORD_PREFIX) ||
                (positions.isNotEmpty() && positions.all { reads.recorded(it.jvm()) })
        }
    }

    /** What keeps [path] in the change set whatever matched it, or null when nothing does. */
    fun protection(path: String, declaration: Declaration): Protection? {
        val segments = path.split('/')
        val directories = segments.dropLast(1)
        return when {
            path in declaration.outsideRoot -> Protection.OUTSIDE_ROOT
            path.startsWith("\"") -> Protection.GIT_QUOTED
            // Gradle's names stay build files on Maven too, so a repository that builds both ways
            // keeps either build's files; a Gradle build treats a pom.xml as any other file.
            declaration.maven && isMavenBuildFile(segments.last(), directories, declaration) -> Protection.BUILD_FILE
            isBuildFile(segments.last(), directories) ||
                declaration.buildLogicDirs.any { path.startsWith("$it/") } ||
                inNestedBuild(declaration.rootDir, directories) -> Protection.BUILD_FILE
            // Any `src` directory, not only the layouts Gradle reports: Android and multiplatform
            // test resources, assets and generator inputs live under one too.
            "src" in directories || declaration.sourceDirs.any { path.startsWith("$it/") } -> Protection.SOURCE_SET
            path in declaration.sourceFiles -> Protection.SOURCE_SET
            else -> null
        }
    }

    private fun isBuildFile(name: String, directories: List<String>): Boolean =
        name.endsWith(".gradle") || name.endsWith(".gradle.kts") || name.endsWith(".versions.toml") ||
            name in BUILD_FILE_NAMES || "gradle" in directories || "buildSrc" in directories

    /** Under a directory below the root that holds its own settings file: another build. */
    private fun inNestedBuild(rootDir: File, directories: List<String>): Boolean =
        directories.indices.any { depth ->
            val dir = File(rootDir, directories.subList(0, depth + 1).joinToString("/"))
            SETTINGS_FILES.any { File(dir, it).isFile }
        }

    private fun isMavenBuildFile(name: String, directories: List<String>, declaration: Declaration): Boolean =
        name in MAVEN_BUILD_FILE_NAMES || name.startsWith("pom.") || ".mvn" in directories ||
            directories.indices.any { depth ->
                val dir = directories.subList(0, depth + 1).joinToString("/")
                dir !in declaration.moduleDirs && File(declaration.rootDir, "$dir/pom.xml").isFile
            }

    // `pom.*` covers the polyglot POMs (`pom.yml`, `pom.kts`) an extension in `.mvn` enables.
    private val MAVEN_BUILD_FILE_NAMES = setOf("mvnw", "mvnw.cmd")

    private val BUILD_FILE_NAMES = setOf("gradle.properties", "gradlew", "gradlew.bat")

    private val SETTINGS_FILES = listOf("settings.gradle", "settings.gradle.kts")

    private val DRIVE = Regex("^[A-Za-z]:")

    /**
     * One change-set list item: path and entry, with `%` and `,` escaped, since the list is
     * comma-separated and either can be in a path. A default entry is written `default:<entry>`.
     */
    fun encode(ignored: Ignored): String = escape(ignored.path) + "\t" + escape(ignored.label)

    /** [value] with `%` and `,` escaped, for a comma-separated change-set list. */
    fun escape(value: String) = value.replace("%", "%25").replace(",", "%2C")
}
