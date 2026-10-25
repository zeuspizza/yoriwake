package io.github.zeuspizza.yoriwake.gradle.change

import java.io.File

/**
 * The build's `pathIgnore`: changed paths its owner declares no test reads, taken out of the change
 * set before anything else sees it. Paths that change what compiles or what a build reads are never
 * taken out, whatever an entry matches, and each match is reported either way, so a wrong
 * declaration can be found.
 */
object PathIgnore {

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

    data class Ignored(val path: String, val entry: String)

    data class Kept(val path: String, val entry: String, val protection: Protection)

    /** The change set after the declaration: [kept] goes on, [ignored] is logged, [notIgnored] too. */
    class Applied(val kept: List<String>, val ignored: List<Ignored>, val notIgnored: List<Kept>) {
        companion object {
            fun none(paths: List<String>) = Applied(paths, emptyList(), emptyList())
        }
    }

    /**
     * The entries and what protects a path from them, as plain values a task action may hold.
     * [sourceDirs] and [buildLogicDirs] are root-relative; [outsideRoot] holds the changed paths git
     * reported outside the Gradle root.
     */
    class Declaration(
        val entries: List<String>,
        val rootDir: File,
        val sourceDirs: List<String>,
        val buildLogicDirs: List<String>,
        val outsideRoot: Set<String>,
    ) : java.io.Serializable

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
        if (declaration == null || declaration.entries.isEmpty()) return Applied.none(paths)
        val matched = paths.distinct().mapNotNull { path ->
            declaration.entries.firstOrNull { matches(it, path) }?.let { path to it }
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
            ignored.map { (path, entry) -> Ignored(path, entry) }.sortedBy { it.path },
            protectedBy.map { (path, why) -> Kept(path, matched.getValue(path), why) }.sortedBy { it.path },
        )
    }

    /** What keeps [path] in the change set whatever matched it, or null when nothing does. */
    fun protection(path: String, declaration: Declaration): Protection? {
        val segments = path.split('/')
        val directories = segments.dropLast(1)
        return when {
            path in declaration.outsideRoot -> Protection.OUTSIDE_ROOT
            path.startsWith("\"") -> Protection.GIT_QUOTED
            isBuildFile(segments.last(), directories) ||
                declaration.buildLogicDirs.any { path.startsWith("$it/") } ||
                inNestedBuild(declaration.rootDir, directories) -> Protection.BUILD_FILE
            // Any `src` directory, not only the layouts Gradle reports: Android and multiplatform
            // test resources, assets and generator inputs live under one too.
            "src" in directories || declaration.sourceDirs.any { path.startsWith("$it/") } -> Protection.SOURCE_SET
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

    private val BUILD_FILE_NAMES = setOf("gradle.properties", "gradlew", "gradlew.bat")

    private val SETTINGS_FILES = listOf("settings.gradle", "settings.gradle.kts")

    private val DRIVE = Regex("^[A-Za-z]:")

    /**
     * One change-set list item: path and entry, with `%` and `,` escaped, since the list is
     * comma-separated and either can be in a path.
     */
    fun encode(ignored: Ignored): String = escape(ignored.path) + "\t" + escape(ignored.entry)

    private fun escape(value: String) = value.replace("%", "%25").replace(",", "%2C")
}
