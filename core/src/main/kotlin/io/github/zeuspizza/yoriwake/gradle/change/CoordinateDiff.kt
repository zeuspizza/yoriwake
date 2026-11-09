package io.github.zeuspizza.yoriwake.gradle.change

/**
 * Whether a changed build file changes dependency versions and nothing else, read from its diff.
 *
 * A text rule, taken before anything resolves: Gradle declares no build script as an input of a test
 * task, so its model cannot say what an edit reaches. The rule only says yes for line shapes it
 * knows, each pair of removed and added lines identical but for one version literal:
 * - a version catalog's `[versions]` value, or a `[libraries]` entry's own version;
 * - a `"group:name:version"` notation inside a `dependencies {}` block of a module build script,
 *   outside `buildscript`, `plugins` and the like;
 * - on Maven, a `<version>` of a `<dependency>` under a POM's own `<dependencies>` or
 *   `<dependencyManagement>`, or a `<properties>` value.
 *
 * Everything else refuses the file, which then forces as before: a version held in a variable, a
 * lockfile, verification metadata, a plugin, a comment, a rich version, a script of `buildSrc`, of
 * `build-logic` or of an included build. What the versions moved is resolution's to say, never this
 * rule's.
 */
internal object CoordinateDiff {

    enum class Shape { CATALOG, SCRIPT, POM }

    /** One version a hunk changed. */
    data class Bump(
        val path: String,
        val shape: Shape,
        /** The `[versions]` key whose value changed. */
        val versionKey: String? = null,
        /** The `[libraries]` alias whose own version changed. */
        val alias: String? = null,
        /** `group:name` of a notation or a library entry. */
        val coordinate: String? = null,
        /** The configuration a notation is declared in. */
        val configuration: String? = null,
    )

    sealed interface Verdict {
        data class Accepted(val bumps: List<Bump>) : Verdict

        /** [hunk] is the first line refused, [why] says why. */
        data class Refused(val path: String, val hunk: String, val why: String) : Verdict
    }

    /**
     * The shape a changed path could have, or null when no hunk of it can be accepted. [includedBuilds]
     * are the directories, relative to the root, of builds this one includes (those holding their own
     * settings file): their scripts build the build, not the tests.
     */
    fun shapeOf(path: String, includedBuilds: Collection<String> = emptyList(), maven: Boolean = false): Shape? {
        val name = path.substringAfterLast('/')
        if (maven) return if (name == "pom.xml") Shape.POM else null
        val dirs = path.split('/').dropLast(1)
        if (dirs.any { it == "buildSrc" || it == "build-logic" }) return null
        if (includedBuilds.any { it.isNotEmpty() && (path.startsWith("$it/")) }) return null
        return when {
            // The build's own catalogs, under the root's `gradle/`; one elsewhere is some other build's, or data.
            name.endsWith(".versions.toml") && dirs == listOf("gradle") -> Shape.CATALOG
            name == "build.gradle" || name == "build.gradle.kts" -> Shape.SCRIPT
            else -> null
        }
    }

    /**
     * Classifies one file from its `git diff -U0` lines and its text before ([old]) and after
     * ([new]) the change.
     */
    fun classify(path: String, shape: Shape, diff: List<String>, old: String, new: String): Verdict {
        val hunks = hunks(diff) ?: return Verdict.Refused(path, diff.firstOrNull().orEmpty(), "the diff could not be read")
        if (hunks.isEmpty()) return Verdict.Refused(path, "", "no line changed")
        val oldLines = old.lines()
        val newLines = new.lines()
        val context = { lines: List<String> ->
            when (shape) {
                Shape.SCRIPT -> blocks(lines)
                Shape.CATALOG -> sections(lines)
                Shape.POM -> pomElements(lines)
            }
        }
        val oldContext = context(oldLines)
        val newContext = context(newLines)
        val bumps = mutableListOf<Bump>()
        for (hunk in hunks) {
            if (hunk.removed.size != hunk.added.size) {
                return Verdict.Refused(path, (hunk.removed + hunk.added).first(), "lines were added or removed, not changed")
            }
            for (i in hunk.removed.indices) {
                val before = hunk.removed[i]
                val after = hunk.added[i]
                val oldAt = oldContext.getOrNull(hunk.oldStart - 1 + i)
                val newAt = newContext.getOrNull(hunk.newStart - 1 + i)
                if (oldAt == null || newAt == null || oldAt != newAt) {
                    return Verdict.Refused(path, after, "the line moved between blocks")
                }
                val bump = when (shape) {
                    Shape.CATALOG -> catalogLine(path, before, after, newAt)
                    Shape.SCRIPT -> scriptLine(path, before, after, newAt)
                    Shape.POM -> pomLine(path, before, after, newAt, newLines, hunk.newStart - 1 + i)
                } ?: return Verdict.Refused(path, after, "not a version change of a shape this rule knows")
                bumps += bump
            }
        }
        return Verdict.Accepted(bumps)
    }

    private class Hunk(val oldStart: Int, val newStart: Int, val removed: List<String>, val added: List<String>)

    private val HEADER = Regex("""^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@.*$""")

    /** The hunks of a `-U0` diff, or null when a line is not one a diff of one text file holds. */
    private fun hunks(diff: List<String>): List<Hunk>? {
        val hunks = mutableListOf<Hunk>()
        var current: Triple<Pair<Int, Int>, MutableList<String>, MutableList<String>>? = null
        fun close() {
            current?.let { (starts, removed, added) ->
                // A pure deletion or insertion names the line before it, so its start is off by one.
                hunks += Hunk(starts.first, starts.second, removed, added)
            }
        }
        for (line in diff) {
            val header = HEADER.matchEntire(line)
            when {
                header != null -> {
                    close()
                    current = Triple(
                        header.groupValues[1].toInt() to header.groupValues[3].toInt(),
                        mutableListOf(), mutableListOf(),
                    )
                }
                current == null -> {
                    // The file header: anything that says the file is new, gone, binary or renamed refuses.
                    if (line.startsWith("new file") || line.startsWith("deleted file") || line.startsWith("Binary") ||
                        line.startsWith("rename ") || line.startsWith("similarity") || line.startsWith("old mode") ||
                        line.startsWith("new mode")
                    ) return null
                }
                line.startsWith("-") -> current.second += line.substring(1)
                line.startsWith("+") -> current.third += line.substring(1)
                line.startsWith("\\") || line.isEmpty() -> Unit
                else -> return null
            }
        }
        close()
        return hunks
    }

    // ---------------------------------------------------------------- catalogs

    /** The `[section]` each line of a TOML file is in, by line. */
    private fun sections(lines: List<String>): List<String> {
        var section = ""
        return lines.map { line ->
            Regex("""^\s*\[\s*([A-Za-z0-9_.-]+)\s*]\s*(#.*)?$""").matchEntire(line)?.let { section = it.groupValues[1]; "[header]" }
                ?: section
        }
    }

    private val KEY = """([A-Za-z0-9_.-]+)"""

    private fun catalogLine(path: String, before: String, after: String, section: String): Bump? {
        val (index, _, newValue) = oneQuotedDiffers(before, after) ?: return null
        val strings = quoted(after)
        return when (section) {
            "versions" -> {
                if (!isVersion(newValue)) return null
                // A dotted key (`lib.strictly`) is a rich version of another key; refused.
                val match = Regex("""^\s*([A-Za-z0-9_-]+)\s*=\s*"[^"]*"\s*$""").matchEntire(after) ?: return null
                Bump(path, Shape.CATALOG, versionKey = match.groupValues[1])
            }
            "libraries" -> {
                val alias = Regex("""^\s*([A-Za-z0-9_-]+)\s*=""").find(after)?.groupValues?.get(1) ?: return null
                // `alias = "group:name:version"`
                if (Regex("""^\s*$KEY\s*=\s*"[^"]*"\s*$""").matches(after)) {
                    val (_, oldCoordinate) = notationParts(quoted(before).getOrNull(0)) ?: return null
                    val (version, coordinate) = notationParts(strings[0]) ?: return null
                    if (oldCoordinate != coordinate || !isVersion(version)) return null
                    return Bump(path, Shape.CATALOG, alias = alias, coordinate = coordinate)
                }
                // `alias = { module = "g:a", version = "v" }` or with `group` and `name`.
                val table = Regex("""^\s*$KEY\s*=\s*\{(.*)}\s*$""").matchEntire(after) ?: return null
                val attributes = attributes(table.groupValues[2]) ?: return null
                if (attributes.keys.any { it !in setOf("module", "group", "name", "version") }) return null
                val changed = attributes.entries.toList().getOrNull(index)?.key
                if (changed != "version" || !isVersion(newValue)) return null
                val coordinate = attributes["module"] ?: run {
                    val group = attributes["group"] ?: return null
                    val name = attributes["name"] ?: return null
                    "$group:$name"
                }
                Bump(path, Shape.CATALOG, alias = alias, coordinate = coordinate)
            }
            else -> null
        }
    }

    /** `key = "value"` pairs of an inline table, in order; null when any value is not a plain string. */
    private fun attributes(body: String): LinkedHashMap<String, String>? {
        val attributes = LinkedHashMap<String, String>()
        for (part in body.split(',')) {
            if (part.isBlank()) continue
            val match = Regex("""^\s*$KEY\s*=\s*"([^"]*)"\s*$""").matchEntire(part) ?: return null
            attributes[match.groupValues[1]] = match.groupValues[2]
        }
        return attributes
    }

    // ---------------------------------------------------------------- build scripts

    /**
     * The blocks each line of a build script is in, outermost first, by line: `dependencies`,
     * `buildscript`, or `?` for a brace no name precedes. Strings and comments are left out.
     */
    private fun blocks(lines: List<String>): List<String> {
        val stack = ArrayDeque<String>()
        var inBlockComment = false
        return lines.map { raw ->
            val at = stack.joinToString("/")
            var line = raw
            if (inBlockComment) {
                val end = line.indexOf("*/")
                if (end < 0) return@map at
                inBlockComment = false
                line = line.substring(end + 2)
            }
            line = stripStrings(line)
            val comment = line.indexOf("//")
            if (comment >= 0) line = line.substring(0, comment)
            val open = line.indexOf("/*")
            if (open >= 0) {
                if (line.indexOf("*/", open + 2) < 0) inBlockComment = true
                line = line.substring(0, open)
            }
            var i = 0
            while (i < line.length) {
                when (line[i]) {
                    '{' -> stack.addLast(Regex("""([A-Za-z_][A-Za-z0-9_]*)\s*(\([^()]*\))?\s*$""").find(line.substring(0, i))?.groupValues?.get(1) ?: "?")
                    '}' -> stack.removeLastOrNull()
                }
                i++
            }
            at
        }
    }

    /** [line] with every string literal's content removed, its quotes kept. */
    private fun stripStrings(line: String): String {
        val out = StringBuilder()
        var quote: Char? = null
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (quote != null) {
                if (c == '\\') { i += 2; continue }
                if (c == quote) { quote = null; out.append(c) }
            } else {
                if (c == '"' || c == '\'') quote = c
                out.append(c)
            }
            i++
        }
        return out.toString()
    }

    /** Blocks a dependency notation in may not be inside: they configure the build, not the tests. */
    private val BUILD_BLOCKS = setOf("buildscript", "plugins", "pluginManagement", "settings", "initscript")

    private fun scriptLine(path: String, before: String, after: String, blocks: String): Bump? {
        val nested = blocks.split('/')
        if ("dependencies" !in nested || nested.any { it in BUILD_BLOCKS }) return null
        val (index, oldValue, newValue) = oneQuotedDiffers(before, after) ?: return null
        val (oldVersion, oldCoordinate) = notationParts(oldValue) ?: return null
        val (newVersion, coordinate) = notationParts(newValue) ?: return null
        if (oldCoordinate != coordinate || !isVersion(oldVersion) || !isVersion(newVersion)) return null
        // The notation is the only string on its line, so the line declares nothing else.
        if (quoted(after).size != 1 || index != 0) return null
        val configuration = Regex("""^\s*([A-Za-z_][A-Za-z0-9_]*)\s*[( ]""").find(after)?.groupValues?.get(1) ?: return null
        return Bump(path, Shape.SCRIPT, coordinate = coordinate, configuration = configuration)
    }

    // ---------------------------------------------------------------- POMs

    /**
     * The elements each line of a POM is in, outermost first, by line, as `project/dependencies/
     * dependency`; `?` marks a line whose elements cannot be told (a comment or CDATA spanning lines,
     * a tag split across lines), which refuses any hunk inside it.
     */
    fun pomElements(lines: List<String>): List<String> {
        val stack = ArrayDeque<String>()
        var unknown = false
        var inComment = false
        var pending = ""
        return lines.map { raw ->
            val at = if (unknown || inComment || pending.isNotEmpty()) "?" else stack.joinToString("/")
            var line = raw
            if (inComment) {
                val end = line.indexOf("-->")
                if (end < 0) return@map at
                inComment = false
                line = line.substring(end + 3)
            }
            line = line.replace(Regex("<!--.*?-->"), " ")
            val open = line.indexOf("<!--")
            if (open >= 0) {
                inComment = true
                line = line.substring(0, open)
            }
            // A tag split across lines, as a `<project xmlns=...>` header often is, completes on a later line.
            line = pending + line
            val unclosed = Regex("""<[^>]*$""").find(line)
            pending = if (unclosed == null) "" else unclosed.value + " "
            if (unclosed != null) line = line.substring(0, unclosed.range.first)
            for (tag in Regex("""<(/?)([A-Za-z_][\w.-]*)[^>]*?(/?)>""").findAll(line)) {
                val (close, name, selfClosing) = tag.destructured
                when {
                    tag.value.startsWith("<?") -> Unit
                    selfClosing == "/" -> Unit
                    close == "/" -> if (stack.lastOrNull() == name) stack.removeLast() else unknown = true
                    else -> stack.addLast(name)
                }
            }
            at
        }
    }

    val DEPENDENCY_PATHS = setOf("project/dependencies/dependency", "project/dependencyManagement/dependencies/dependency")

    private fun pomLine(path: String, before: String, after: String, elements: String, lines: List<String>, index: Int): Bump? {
        val pattern = Regex("""^\s*<([A-Za-z_][\w.-]*)>([^<]*)</\1>\s*$""")
        val old = pattern.matchEntire(before) ?: return null
        val new = pattern.matchEntire(after) ?: return null
        if (old.groupValues[1] != new.groupValues[1] || before.replace(old.groupValues[2], "\u0000") != after.replace(new.groupValues[2], "\u0000")) return null
        if (!isVersion(old.groupValues[2]) || !isVersion(new.groupValues[2])) return null
        val name = new.groupValues[1]
        return when {
            elements == "project/properties" -> Bump(path, Shape.POM, versionKey = name)
            elements in DEPENDENCY_PATHS && name == "version" -> Bump(path, Shape.POM, coordinate = pomCoordinate(lines, index) ?: "?")
            else -> null
        }
    }

    /** `group:artifact` of the `<dependency>` around line [index], or null when it names no literal one. */
    fun pomCoordinate(lines: List<String>, index: Int): String? {
        var start = index
        while (start > 0 && !lines[start].contains("<dependency>")) start--
        var end = index
        while (end < lines.size - 1 && !lines[end].contains("</dependency>")) end++
        val block = lines.subList(start, end + 1).joinToString("\n")
        val group = Regex("""<groupId>\s*([^<\s]+)\s*</groupId>""").find(block)?.groupValues?.get(1)
        val artifact = Regex("""<artifactId>\s*([^<\s]+)\s*</artifactId>""").find(block)?.groupValues?.get(1)
        return if (group == null || artifact == null || '$' in group || '$' in artifact) null else "$group:$artifact"
    }

    // ---------------------------------------------------------------- shared

    /** `version` and `group:name` of a `group:name:version` notation, a classifier or extension kept out. */
    private fun notationParts(notation: String?): Pair<String, String>? {
        val parts = notation?.split(':') ?: return null
        if (parts.size !in 3..4 || parts.any(String::isEmpty)) return null
        val version = parts[2].substringBefore('@')
        return version to "${parts[0]}:${parts[1]}"
    }

    /** A literal version: no interpolation, no range, no rich-version syntax. */
    private fun isVersion(value: String): Boolean = Regex("""[0-9A-Za-z][0-9A-Za-z.+_-]*""").matches(value)

    /** Each double- or single-quoted string's content in [line], in order. */
    private fun quoted(line: String): List<String> =
        Regex(""""([^"\\]*)"|'([^'\\]*)'""").findAll(line).map { it.groupValues[1].ifEmpty { it.groupValues[2] } }.toList()

    /**
     * Where the only quoted string that differs between [before] and [after] is, with both values;
     * null when anything outside the strings differs, or more or fewer than one string does.
     */
    private fun oneQuotedDiffers(before: String, after: String): Triple<Int, String, String>? {
        val pattern = Regex(""""[^"\\]*"|'[^'\\]*'""")
        if (before.replace(pattern, "\u0000") != after.replace(pattern, "\u0000")) return null
        val old = quoted(before)
        val new = quoted(after)
        if (old.size != new.size) return null
        val differing = old.indices.filter { old[it] != new[it] }
        val index = differing.singleOrNull() ?: return null
        return Triple(index, old[index], new[index])
    }
}
