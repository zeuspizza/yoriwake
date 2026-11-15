package io.github.zeuspizza.yoriwake.gradle.change

import java.io.File
import java.util.zip.ZipFile

/**
 * What `narrowDependencies` makes of a change set holding build files: a file that changes
 * dependency versions and nothing else ([CoordinateDiff]) is accepted instead of forcing, and the
 * resolved classpath, compared with the one the map recorded ([ClasspathRecord]), says which modules
 * moved. Each moved module then selects the tests that reached its jar; everything a bump can do that
 * no record bounds forces, each under its own [RefusalKind]:
 * - a module new to the classpath, a changed order, a bumped module that now shadows a later entry;
 * - a bumped coordinate on no compared classpath, or declared in a configuration it does not extend;
 * - a bumped module's jar named in the test JVM's arguments;
 * - a version key a plugin shares, a library alias build logic names, a version read as a value;
 * - a build script value read anywhere but a notation's version, read in bulk or by a computed name.
 *
 * Bytes an own class compiled against the new version holds (an inlined constant, a Kotlin `inline`
 * body) are the class-digest rule's, which refuses whenever it cannot compare.
 */
internal object DependencyRule {

    /** What the host knows about the test task that a bump is judged against. */
    class Context(
        /** git's answer by line, blank lines left out. */
        val git: ChangeDetection.Runner,
        /** git's whole answer, or null when it could not answer. */
        val raw: (List<String>) -> String?,
        /** The classpath the test JVM starts with, as [ClasspathRecord] records it; null when it cannot be told. */
        val current: () -> ClasspathRecord.Record?,
        /** The test JVM's arguments and system property values. */
        val jvmArguments: () -> List<String>,
        /**
         * The configurations this task's classpaths are resolved from, with every one they extend;
         * null when they cannot be told, which forces a bump in a build script.
         */
        val configurations: () -> Set<String>?,
        /**
         * Whether a changed module script or POM is one this build reads: a module's own build script
         * or a reactor POM. One that only looks like it (a test's fixture, another build's) is data a
         * test may read, and stays a path coverage cannot see.
         */
        val ownBuildFile: (String) -> Boolean,
        /** On Maven, the reactor's POMs, which are the only build files a bump may change; see [Maven]. */
        val maven: Maven? = null,
    )

    /** What a Maven build tells about its POMs, for the rules on a bumped property. */
    class Maven(
        /** Every POM of the reactor, relative to the root. */
        val poms: () -> List<String>,
        /** Whether any module's parent POM lies outside the checkout, where it may read or declare any property. */
        val outsideParent: () -> Boolean,
    )

    class Inputs(
        val rootDir: File,
        /** The run's changed paths, relative to [rootDir]. */
        val paths: List<String>,
        /** The base the change set was taken against. */
        val against: String,
        val mapDir: File,
        val context: Context,
    ) {
        val git get() = context.git
    }

    sealed interface Verdict {
        /** No changed path is a build file this rule reads. */
        data object NotApplicable : Verdict

        /** A changed build file is not a version bump: it stays a path coverage cannot see. */
        data class Unaccepted(val path: String, val why: String) : Verdict

        data class Forced(val kind: RefusalKind, val reason: String) : Verdict

        data class Accepted(
            /** The build files accepted, which leave the change set. */
            val paths: List<String>,
            /** The identities of the modules that moved or are gone. */
            val modules: List<String>,
            /** The directories whose entry names a moved module changed. */
            val directories: List<String>,
            /** The classpath-files rows that belong to a moved module's jar, old or new. */
            val discount: (String) -> Boolean,
        ) : Verdict
    }

    fun decide(inputs: Inputs): Verdict {
        val maven = inputs.context.maven != null
        val includedBuilds = if (maven) emptyList() else includedBuilds(inputs.git)
        val candidates = inputs.paths.filter { path ->
            when (CoordinateDiff.shapeOf(path, includedBuilds, maven)) {
                null -> false
                CoordinateDiff.Shape.CATALOG -> true
                else -> runCatching { inputs.context.ownBuildFile(path) }.getOrDefault(false)
            }
        }
        if (candidates.isEmpty()) return Verdict.NotApplicable
        val bumps = mutableListOf<CoordinateDiff.Bump>()
        val catalogs = mutableMapOf<String, String>()
        for (path in candidates) {
            val shape = CoordinateDiff.shapeOf(path, includedBuilds, maven)!!
            val new = runCatching { File(inputs.rootDir, path).readText() }.getOrNull()
                ?: return Verdict.Unaccepted(path, "the file is gone")
            // `./`: the path is relative to the root project, which git runs in, not to the repository.
            val old = inputs.context.raw(listOf("show", "${inputs.against}:./$path"))
                ?: return Verdict.Unaccepted(path, "the file is new")
            val diff = inputs.git.run(
                listOf("diff", "-U0", "--no-color", "--no-ext-diff", "--no-renames", inputs.against, "--", path)
            ) ?: return Verdict.Unaccepted(path, "git gave no diff of it")
            when (val verdict = CoordinateDiff.classify(path, shape, diff, old, new)) {
                is CoordinateDiff.Verdict.Refused -> return Verdict.Unaccepted(path, "${verdict.why}: `${verdict.hunk.trim()}`")
                is CoordinateDiff.Verdict.Accepted -> bumps += verdict.bumps
            }
            if (shape == CoordinateDiff.Shape.CATALOG) catalogs[path] = new
        }
        val propertyReaders = mutableMapOf<String, List<String>>()
        val valueReaders = mutableMapOf<String, List<Pair<String, String>>>()
        if (inputs.context.maven != null) {
            pomReads(inputs, inputs.context.maven, bumps, propertyReaders)?.let { return it }
        } else {
            buildLogicReads(inputs, bumps, catalogs, includedBuilds)?.let { return it }
            valueReads(inputs, bumps, includedBuilds, valueReaders)?.let { return it }
        }

        val recorded = ClasspathRecord.readMap(inputs.mapDir)
            ?: return Verdict.Forced(
                RefusalKind.CLASSPATH_UNRECORDED,
                "${candidates.joinToString()} change dependency versions only, but the map records no resolved " +
                    "classpath to compare against; a map recorded before it did looks like this, and the next capture records one",
            )
        val current = runCatching { inputs.context.current() }.getOrNull()
            ?: return Verdict.Forced(
                RefusalKind.CLASSPATH_UNRECORDED,
                "${candidates.joinToString()} change dependency versions only, but this task's resolved classpath could not be read",
            )
        located(inputs, bumps, catalogs, propertyReaders, valueReaders, recorded, current)?.let { return it }
        return compare(inputs, candidates, recorded, current)
    }

    /** Directories of builds this one includes: each holds a settings file of its own. */
    private fun includedBuilds(git: ChangeDetection.Runner): List<String> =
        git.run(listOf("ls-files", "--cached", "--others", "--exclude-standard", "--", "*settings.gradle", "*settings.gradle.kts"))
            .orEmpty().map { it.substringBeforeLast('/', "") }.filter(String::isNotEmpty).distinct()

    /**
     * The refusals for a version the build reads where no classpath shows it: a version key a
     * `[plugins]` entry shares, a library alias named in build logic, a settings file or a
     * `buildscript` block, or a version key read as a value anywhere a build is configured. A text
     * search, so a name that only looks alike forces too.
     */
    private fun buildLogicReads(
        inputs: Inputs,
        bumps: List<CoordinateDiff.Bump>,
        catalogs: Map<String, String>,
        includedBuilds: List<String>,
    ): Verdict? {
        val keys = bumps.mapNotNull { it.versionKey }.distinct()
        val aliases = (bumps.mapNotNull { it.alias } + keys.flatMap { key -> catalogs.values.flatMap { librariesUsing(it, key).keys } }).distinct()
        if (keys.isEmpty() && aliases.isEmpty()) return null
        fun unaccepted(why: String) = Verdict.Unaccepted(bumps.first { it.shape == CoordinateDiff.Shape.CATALOG }.path, why)
        for ((path, text) in catalogs) {
            for (key in keys) {
                if (pluginsUsing(text, key)) {
                    return unaccepted("the version key `$key` is also used outside a plain `[libraries]` entry in $path, as a plugin's version is")
                }
            }
        }
        val sources = inputs.git.run(
            listOf(
                "ls-files", "--cached", "--others", "--exclude-standard", "--",
                "*.gradle", "*.gradle.kts", "buildSrc/*", "build-logic/*",
            ) + includedBuilds.map { "$it/*" }
        ).orEmpty().filter { path -> SOURCE_SUFFIXES.any(path::endsWith) }.distinct()
        for (path in sources) {
            val text = runCatching { File(inputs.rootDir, path).readText() }.getOrNull() ?: continue
            for (key in keys) {
                val dotted = Regex.escape(key.replace('-', '.').replace('_', '.'))
                if (Regex("""versions\.$dotted(?![A-Za-z0-9_])""").containsMatchIn(text) ||
                    Regex("""findVersion\(\s*["']${Regex.escape(key)}["']""").containsMatchIn(text)
                ) {
                    return unaccepted("$path reads the version `$key` as a value, which reaches a test through no classpath")
                }
            }
            val buildLogic = path.substringAfterLast('/').startsWith("settings.gradle") ||
                path.startsWith("buildSrc/") || path.startsWith("build-logic/") ||
                includedBuilds.any { path.startsWith("$it/") } || !path.substringAfterLast('/').startsWith("build.gradle")
            for (alias in aliases) {
                val accessor = Regex("""\.${Regex.escape(alias.replace('-', '.').replace('_', '.'))}(?![A-Za-z0-9_])""")
                val named = if (buildLogic) accessor.containsMatchIn(text) else inBuildscript(text, accessor)
                if (named) return unaccepted("$path names the library `$alias` where the build itself is configured")
            }
        }
        return null
    }

    /**
     * On Maven, a bumped property is accepted only when every `${'$'}{key}` that reads
     * it, across the reactor's POMs, is the `<version>` of a dependency of their own `<dependencies>`
     * or `<dependencyManagement>`, at least one does, no parent POM lies outside the checkout and no
     * POM imports a BOM: both may read or declare any property, unseen. [readers] gets each bumped
     * property's readers by `group:name`.
     */
    private fun pomReads(inputs: Inputs, maven: Maven, bumps: List<CoordinateDiff.Bump>, readers: MutableMap<String, List<String>>): Verdict? {
        val keys = bumps.mapNotNull { it.versionKey }.distinct()
        if (keys.isEmpty()) return null
        val path = bumps.first { it.versionKey != null }.path
        if (runCatching { maven.outsideParent() }.getOrDefault(true)) {
            return Verdict.Unaccepted(path, "a parent POM outside the checkout may read or declare the property `${keys.first()}`")
        }
        val poms = runCatching { maven.poms() }.getOrNull() ?: return Verdict.Unaccepted(path, "the reactor's POMs could not be listed")
        val texts = poms.associateWith { runCatching { File(inputs.rootDir, it).readText() }.getOrNull() ?: return Verdict.Unaccepted(it, "it could not be read") }
        texts.entries.firstOrNull { Regex("""<scope>\s*import\s*</scope>""").containsMatchIn(it.value) }?.let { (pom, _) ->
            return Verdict.Unaccepted(path, "$pom imports a BOM, which may read or declare the property `${keys.first()}`")
        }
        for (key in keys) {
            val found = mutableListOf<String>()
            val reference = "${'$'}{$key}"
            for ((pom, text) in texts) {
                val lines = text.lines()
                val elements = CoordinateDiff.pomElements(lines)
                for ((index, line) in lines.withIndex()) {
                    if (reference !in line) continue
                    val dependency = elements[index] in CoordinateDiff.DEPENDENCY_PATHS &&
                        Regex("""^\s*<version>\s*${Regex.escape(reference)}\s*</version>\s*$""").matches(line)
                    if (!dependency) return Verdict.Unaccepted(path, "$pom reads the property `$key` outside a dependency's version: `${line.trim()}`")
                    found += CoordinateDiff.pomCoordinate(lines, index) ?: return Verdict.Unaccepted(path, "$pom reads `$key` in a dependency it names no literal coordinate for")
                }
            }
            if (found.isEmpty()) return Verdict.Unaccepted(path, "no POM of the checkout reads the property `$key`, so whatever reads it lies outside")
            readers[key] = found
        }
        return null
    }

    private val SOURCE_SUFFIXES = listOf(".gradle", ".gradle.kts", ".kt", ".kts", ".java", ".groovy")

    /**
     * A build script value whose version changed is accepted only when every place the build names
     * it is the version of a dependency notation [CoordinateDiff] reads: then the resolved classpath
     * shows all the change did. [readers] gets each value's notations, by `group:name` and
     * configuration. A value named anywhere else (a system property, a tool's version, `buildSrc`, a
     * plugin's classpath), named nowhere, or reachable unnamed (the build's properties read in bulk,
     * a lookup by a computed name) stays a path coverage cannot see. A text search, so a name that
     * only looks alike refuses too.
     */
    private fun valueReads(
        inputs: Inputs,
        bumps: List<CoordinateDiff.Bump>,
        includedBuilds: List<String>,
        readers: MutableMap<String, List<Pair<String, String>>>,
    ): Verdict? {
        val values = bumps.filter { it.valueName != null }
        if (values.isEmpty()) return null
        val first = values.first()
        fun unaccepted(why: String) = Verdict.Unaccepted(first.path, why)
        val sources = inputs.git.run(
            listOf(
                "ls-files", "--cached", "--others", "--exclude-standard", "--",
                "*.gradle", "*.gradle.kts", "buildSrc/*", "build-logic/*", "gradle.properties", "*/gradle.properties",
            ) + includedBuilds.map { "$it/*" }
        ).orEmpty().filter { path -> path.substringAfterLast('/') == "gradle.properties" || SOURCE_SUFFIXES.any(path::endsWith) }.distinct()
        val texts = sources.mapNotNull { path -> runCatching { File(inputs.rootDir, path).readText() }.getOrNull()?.let { path to it } }
        val scanned = texts.associate { (path, text) -> path to if (path.endsWith("gradle.properties")) null else scan(text) }
        for ((path, scan) in scanned) {
            scan ?: continue
            BULK_READ.find(scan.masked)?.let {
                return unaccepted("$path reads the build's properties in bulk (`${it.value.trim()}`), which hands over the value `${first.valueName}` unnamed")
            }
            DYNAMIC_LOOKUPS.firstNotNullOfOrNull { it.find(scan.masked) }?.let {
                return unaccepted("$path looks a property up by a name it computes (`${lineAt(texts.first { t -> t.first == path }.second, it.range.first).trim()}`), which may be the value `${first.valueName}`")
            }
        }
        for (name in values.map { it.valueName!! }.distinct()) {
            val word = Regex("(?<![A-Za-z0-9_])${Regex.escape(name)}(?![A-Za-z0-9_])")
            val notation = notationOf(name)
            val found = mutableListOf<Pair<String, String>>()
            for ((path, text) in texts) {
                val scan = scanned.getValue(path)
                if (scan == null) {
                    if (word.containsMatchIn(text)) return unaccepted("$path names the value `$name`, which is no dependency notation's version")
                    continue
                }
                val script = path.endsWith(".gradle") || path.endsWith(".gradle.kts")
                val buildLogic = path.substringAfterLast('/').startsWith("settings.gradle") ||
                    path.startsWith("buildSrc/") || path.startsWith("build-logic/") || path.contains("/buildSrc/") ||
                    includedBuilds.any { path.startsWith("$it/") }
                val lineStarts = listOf(0) + text.indices.filter { text[it] == '\n' }.map { it + 1 }
                val lineOf = { offset: Int -> lineStarts.binarySearch(offset).let { if (it >= 0) it else -it - 2 } }
                val blocks by lazy { CoordinateDiff.blocks(text.lines()) }
                val occurrences = word.findAll(scan.masked).map { it.range.first } +
                    scan.strings.filter { it.plain && it.content(text) == name }.map { it.start }
                for (offset in occurrences) {
                    val line = lineOf(offset)
                    if (values.any { it.valueName == name && it.path == path && it.line == line }) continue
                    // The outermost string around the occurrence: a notation names the value in its version.
                    val outer = scan.strings.filter { it.start < offset && offset < it.end }.minByOrNull { it.start }
                    val reader = outer?.takeIf { script && !buildLogic && it.quote == "\"" }?.let { string ->
                        val parts = notation.matchEntire(string.content(text)) ?: return@let null
                        if (lineOf(string.end - 1) != line || blocks.getOrNull(line) !in CoordinateDiff.NOTATION_BLOCKS) return@let null
                        val start = lineStarts[line]
                        val end = lineStarts.getOrNull(line + 1)?.minus(1) ?: text.length
                        val prefix = scan.masked.substring(start, string.start)
                        val suffix = scan.masked.substring(string.end, end)
                        val configuration = NOTATION_PREFIX.matchEntire(prefix)?.groupValues?.get(1) ?: return@let null
                        if (!NOTATION_SUFFIX.matches(suffix) || prefix.count { it == '(' } != suffix.count { it == ')' }) return@let null
                        "${parts.groupValues[1]}:${parts.groupValues[2]}" to configuration
                    } ?: return unaccepted("$path names the value `$name` outside a dependency notation's version: `${lineAt(text, offset).trim()}`")
                    found += reader
                }
            }
            if (found.isEmpty()) return unaccepted("no build script of the checkout reads the value `$name`, so whatever reads it lies outside")
            readers[name] = found.distinct()
        }
        return null
    }

    /**
     * A dependency notation's whole string whose version reads [name]: `g:a:${'$'}name`,
     * `g:a:${'$'}{rootProject.ext.name}`, or an accessor naming it in full (`extra["name"]`,
     * `property("name")`).
     */
    private fun notationOf(name: String): Regex {
        val n = Regex.escape(name)
        val qualifier = "(?:[A-Za-z_][A-Za-z0-9_]*\\.)*"
        val literal = "[\"']$n[\"']"
        val expression = "$qualifier(?:$n|(?:ext|extra)\\[\\s*$literal\\s*]|(?:ext|extra)\\.get\\(\\s*$literal\\s*\\)|(?:property|findProperty)\\(\\s*$literal\\s*\\))"
        return Regex("^([^:\"'$\\s]+):([^:\"'$\\s]+):(?:\\$\\{\\s*$expression\\s*}|\\$$qualifier$n)$")
    }

    /** What precedes a notation on its line, comments blanked: its configuration, maybe a platform wrapper. */
    private val NOTATION_PREFIX = Regex("""^\s*([A-Za-z_][A-Za-z0-9_]*)\s*(?:\(\s*)?(?:(?:platform|enforcedPlatform)\s*\(\s*)?$""")
    private val NOTATION_SUFFIX = Regex("""^\s*\)?\s*\)?\s*;?\s*$""")

    /** A read of every property at once: `project.properties` holds every `ext` value too. */
    private val BULK_READ = Regex("""(?<![A-Za-z0-9_])(?:properties|getProperties|gradlePropertiesPrefixedBy)(?![A-Za-z0-9_])""")

    /** A property looked up by anything but a whole literal name; string contents are blanked to [BLANK]. */
    private val DYNAMIC_LOOKUPS = listOf(
        Regex("""(?<![A-Za-z0-9_])(?<!objects\.)(?:property|findProperty|getProperty|hasProperty|gradleProperty)\s*+(?:\(\s*+)?+(?!["']\u0001*+["'])"""),
        Regex("""(?<![A-Za-z0-9_])(?:ext|extra)\s*+\[\s*+(?!["']\u0001*+["']\s*+])"""),
        Regex("""(?<![A-Za-z0-9_])(?:ext|extra)\s*+\.\s*+(?:get|set|has)\s*+\(\s*+(?!["']\u0001*+["'])"""),
    )

    private const val BLANK = '\u0001'

    private fun lineAt(text: String, offset: Int): String =
        text.substring(text.lastIndexOf('\n', offset - 1) + 1, text.indexOf('\n', offset).let { if (it < 0) text.length else it })

    /** A string literal of a script, from its opening quote to past its closing one. */
    private class StringLiteral(val start: Int, val end: Int, val quote: String, val plain: Boolean) {
        fun content(text: String) = text.substring(start + quote.length, maxOf(start + quote.length, end - quote.length))
    }

    /** A script with comments blanked and string contents set to [BLANK], interpolations kept, and its strings. */
    private class Scan(val masked: String, val strings: List<StringLiteral>)

    /**
     * Reads a Kotlin, Groovy or Java source as code, comments and strings, an interpolation
     * (`${'$'}name`, `${'$'}{...}`) of a double-quoted string read as code.
     */
    private fun scan(text: String): Scan {
        val out = StringBuilder(text)
        val strings = mutableListOf<StringLiteral>()
        fun blank(from: Int, to: Int, with: Char) {
            for (k in from until minOf(to, text.length)) if (text[k] != '\n') out.setCharAt(k, with)
        }
        lateinit var code: (Int, Boolean) -> Int
        fun string(start: Int): Int {
            val quote = when {
                text.startsWith("\"\"\"", start) -> "\"\"\""
                text.startsWith("'''", start) -> "'''"
                else -> text[start].toString()
            }
            val interpolating = quote[0] == '"'
            var plain = true
            var i = start + quote.length
            while (i < text.length) {
                if (text.startsWith(quote, i)) { i += quote.length; break }
                val c = text[i]
                if (quote.length == 1 && c == '\n') break
                when {
                    c == '\\' && quote.length == 1 -> { blank(i, i + 2, BLANK); i += 2 }
                    interpolating && c == '$' && text.getOrNull(i + 1) == '{' -> {
                        plain = false
                        i = code(i + 2, true) + 1
                    }
                    interpolating && c == '$' && text.getOrNull(i + 1)?.let { it.isLetter() || it == '_' } == true -> {
                        plain = false
                        i++
                        while (i < text.length && (text[i].isLetterOrDigit() || text[i] == '_' ||
                                (text[i] == '.' && text.getOrNull(i + 1)?.let { it.isLetter() || it == '_' } == true))
                        ) i++
                    }
                    else -> { blank(i, i + 1, BLANK); i++ }
                }
            }
            strings += StringLiteral(start, minOf(i, text.length), quote, plain)
            return i
        }
        code = { from, nested ->
            var i = from
            var depth = 0
            var result = text.length
            while (i < text.length) {
                val c = text[i]
                when {
                    text.startsWith("//", i) -> {
                        val end = text.indexOf('\n', i).let { if (it < 0) text.length else it }
                        blank(i, end, ' '); i = end
                    }
                    text.startsWith("/*", i) -> {
                        val end = text.indexOf("*/", i + 2).let { if (it < 0) text.length else it + 2 }
                        blank(i, end, ' '); i = end
                    }
                    c == '"' || c == '\'' -> i = string(i)
                    c == '{' -> { depth++; i++ }
                    c == '}' -> {
                        if (nested && depth == 0) { result = i; break }
                        depth--; i++
                    }
                    else -> i++
                }
            }
            result
        }
        code(0, false)
        return Scan(out.toString(), strings)
    }

    /** Whether [accessor] occurs inside a `buildscript {}` block of [text]. */
    private fun inBuildscript(text: String, accessor: Regex): Boolean {
        var at = 0
        while (true) {
            val start = Regex("""\bbuildscript\s*\{""").find(text, at) ?: return false
            var depth = 0
            var end = start.range.last
            while (end < text.length) {
                when (text[end]) {
                    '{' -> depth++
                    '}' -> if (--depth == 0) break
                }
                end++
            }
            if (accessor.containsMatchIn(text.substring(start.range.first, minOf(end + 1, text.length)))) return true
            at = end + 1
        }
    }

    private fun section(text: String, name: String): List<String> {
        val lines = mutableListOf<String>()
        var inside = false
        for (line in text.lines()) {
            val header = Regex("""^\s*\[\s*([A-Za-z0-9_.-]+)\s*]""").find(line)
            if (header != null) {
                inside = header.groupValues[1] == name
                continue
            }
            if (inside) lines += line
        }
        return lines
    }

    private fun refersTo(line: String, key: String) =
        Regex("""version\.ref\s*=\s*"${Regex.escape(key)}"""").containsMatchIn(line) ||
            Regex("""version\s*=\s*\{\s*ref\s*=\s*"${Regex.escape(key)}"""").containsMatchIn(line)

    /**
     * Whether [key] is named anywhere in [catalog] but a `[versions]` line and a `[libraries]` entry
     * of the one inline form [librariesUsing] reads: by a plugin, a table-form entry
     * (`[libraries.x]`), a single-quoted reference. Any of them reads the version where no classpath
     * shows it, or where this rule cannot tell which library it is.
     */
    private fun pluginsUsing(catalog: String, key: String): Boolean {
        val quoted = Regex("""["']${Regex.escape(key)}["']""")
        var section = ""
        for (line in catalog.lines()) {
            Regex("""^\s*\[\s*([^\]]+?)\s*]""").find(line)?.let { section = it.groupValues[1]; return@let }
            if (section == "versions" || !quoted.containsMatchIn(line)) continue
            if (section == "libraries" && refersTo(line, key)) continue
            return true
        }
        return false
    }

    /** Alias to `group:name` of each `[libraries]` entry whose version is [key]. */
    private fun librariesUsing(catalog: String, key: String): Map<String, String?> =
        section(catalog, "libraries").filter { refersTo(it, key) }.associate { line ->
            val alias = line.substringBefore('=').trim()
            val module = Regex("""module\s*=\s*"([^"]+)"""").find(line)?.groupValues?.get(1)
            val group = Regex("""group\s*=\s*"([^"]+)"""").find(line)?.groupValues?.get(1)
            val name = Regex("""name\s*=\s*"([^"]+)"""").find(line)?.groupValues?.get(1)
            alias to (module ?: if (group != null && name != null) "$group:$name" else null)
        }

    /**
     * Every bumped coordinate is a module of this task's classpath, before or now, and every
     * notation is declared in a configuration it extends. A bump in an agent's or a generator's
     * configuration moves no compared classpath, so it would select nothing.
     */
    private fun located(
        inputs: Inputs,
        bumps: List<CoordinateDiff.Bump>,
        catalogs: Map<String, String>,
        /** On Maven, each bumped property's readers, by `group:name`. */
        propertyReaders: Map<String, List<String>>,
        /** Each bumped build script value's notations, by `group:name` and configuration. */
        valueReaders: Map<String, List<Pair<String, String>>>,
        recorded: ClasspathRecord.Record,
        current: ClasspathRecord.Record,
    ): Verdict? {
        val onClasspath = (recorded.modules + current.modules).mapTo(HashSet()) { coordinateOf(it.identity) }
        for (bump in bumps) {
            val declared: List<Pair<String, String?>> = when {
                bump.coordinate == "?" -> return unlocated("a dependency bumped in ${bump.path} names no literal `groupId` and `artifactId`")
                bump.coordinate != null -> listOf(bump.coordinate to bump.configuration)
                bump.valueName != null -> valueReaders[bump.valueName].orEmpty()
                bump.versionKey != null && bump.shape == CoordinateDiff.Shape.POM -> propertyReaders[bump.versionKey].orEmpty().map { it to null }
                bump.versionKey != null -> catalogs.values.flatMap { librariesUsing(it, bump.versionKey).values }
                    .map { (it ?: return unlocated("the version `${bump.versionKey}` is used by a library whose coordinate cannot be read")) to null }
                else -> emptyList()
            }
            for ((coordinate, configuration) in declared) {
                if (coordinate !in onClasspath) {
                    return unlocated("`$coordinate`, bumped in ${bump.path}, is on none of this task's classpaths")
                }
                configuration ?: continue
                val configurations = runCatching { inputs.context.configurations() }.getOrNull()
                if (configurations == null || configuration !in configurations) {
                    return unlocated("`$coordinate`, bumped in ${bump.path}, is declared in `$configuration`, which this task's classpath does not extend")
                }
            }
        }
        return null
    }

    private fun unlocated(why: String) = Verdict.Forced(RefusalKind.DEPENDENCY_UNLOCATED, why)

    /** `group:name` of a module identity. */
    fun coordinateOf(identity: String): String = identity.split(':').take(2).joinToString(":")

    private fun compare(
        inputs: Inputs,
        candidates: List<String>,
        recorded: ClasspathRecord.Record,
        current: ClasspathRecord.Record,
    ): Verdict {
        val before = recorded.modules.associateBy { it.identity }
        val now = current.modules.associateBy { it.identity }
        val added = now.keys - before.keys
        if (added.isNotEmpty()) {
            return Verdict.Forced(
                RefusalKind.DEPENDENCY_ADDED,
                "the bump in ${candidates.joinToString()} puts ${added.sorted().joinToString()} on this task's classpath, " +
                    "which the map never saw",
            )
        }
        val sequence = { record: ClasspathRecord.Record, other: Set<String> -> record.entries.map { it.identity }.filter { it in other } }
        val beforeIds = recorded.entries.mapTo(HashSet()) { it.identity }
        val nowIds = current.entries.mapTo(HashSet()) { it.identity }
        if (sequence(recorded, nowIds) != sequence(current, beforeIds)) {
            return Verdict.Forced(RefusalKind.CLASSPATH_REORDERED, "the bump in ${candidates.joinToString()} changed this task's classpath order")
        }
        val gone = before.keys - now.keys
        // A digest that could not be read at either end never vouches for an unchanged jar.
        val moved = now.values.filter { entry ->
            before[entry.identity].let {
                it != null && (it.key != entry.key || it.digest != entry.digest || entry.digest == ClasspathFiles.UNREADABLE)
            }
        }
        val arguments = runCatching { inputs.context.jvmArguments() }.getOrNull()
            ?: return Verdict.Forced(RefusalKind.DEPENDENCY_IN_JVM_ARGUMENTS, "the test JVM's arguments could not be read, so a bumped `-javaagent` cannot be ruled out")
        for (entry in moved) {
            val file = entry.file ?: continue
            for (argument in arguments) {
                val oldName = before.getValue(entry.identity).key.substringAfterLast('/')
                if (argument.contains(file.absolutePath) || argument.contains(file.name) || argument.contains(oldName)) {
                    return Verdict.Forced(
                        RefusalKind.DEPENDENCY_IN_JVM_ARGUMENTS,
                        "the bumped ${entry.identity} is named in the test JVM's arguments ($argument), so it acts before anything is recorded",
                    )
                }
            }
        }
        val directories = sortedSetOf<String>()
        for (entry in moved) {
            val old = recorded.dirs[entry.identity].orEmpty()
            val new = current.dirs[entry.identity].orEmpty()
            if (old[ClasspathRecord.MANIFEST] == null || old[ClasspathRecord.MANIFEST] != new[ClasspathRecord.MANIFEST]) {
                return Verdict.Forced(
                    RefusalKind.DEPENDENCY_ADDED,
                    "the bumped ${entry.identity} changed what its manifest gives the class loader (`Class-Path`, " +
                        "`Multi-Release`), or the map does not record it, so other files may be on the class path",
                )
            }
            val changed = (old.keys + new.keys).filter { dir ->
                dir != ClasspathRecord.MANIFEST &&
                    (old[dir] == null || old[dir] != new[dir] || old[dir] == ClasspathFiles.UNREADABLE)
            }
            directories += changed
            shadowed(entry, changed, current)?.let { return it }
        }
        val keys = (moved.flatMap { listOf(it.key, before.getValue(it.identity).key) } + gone.map { before.getValue(it).key }).toSet()
        val discount = { row: String ->
            row == ClasspathFiles.DEPENDENCY_ORDER || keys.any { key -> row == key || row.startsWith("$key!/") || row.startsWith("$key/") }
        }
        return Verdict.Accepted(candidates, (moved.map { it.identity } + gone).sorted(), directories.toList(), discount)
    }

    /**
     * [DEPENDENCY_SHADOWS][RefusalKind.DEPENDENCY_SHADOWS] when a class of [entry]'s new jar, in one of
     * the [changed] directories, is also held by an entry after it: a test that found the class there
     * reached the later entry, and the lookup that missed in this jar was answered, so nothing records it.
     */
    private fun shadowed(entry: ClasspathRecord.Entry, changed: List<String>, current: ClasspathRecord.Record): Verdict? {
        if (changed.isEmpty()) return null
        val file = entry.file ?: return null
        val classes = runCatching {
            ZipFile(file).use { zip ->
                zip.entries().asSequence().map { it.name }
                    .filter { it.endsWith(".class") && (it.substringBeforeLast('/', "").let { d -> if (d.isEmpty()) "/" else "$d/" }) in changed }
                    .toList()
            }
        }.getOrElse { return shadows(entry, "its jar could not be listed") }
        if (classes.isEmpty()) return null
        val later = current.entries.dropWhile { it !== entry }.drop(1)
        for (other in later) {
            val otherFile = other.file ?: continue
            val held = when {
                otherFile.isDirectory -> classes.firstOrNull { File(otherFile, it).isFile }
                otherFile.isFile -> runCatching { ZipFile(otherFile).use { zip -> classes.firstOrNull { zip.getEntry(it) != null } } }
                    .getOrElse { return shadows(entry, "${otherFile.name} could not be listed") }
                else -> null
            }
            if (held != null) return shadows(entry, "${other.identity} also holds $held")
        }
        return null
    }

    private fun shadows(entry: ClasspathRecord.Entry, why: String) = Verdict.Forced(
        RefusalKind.DEPENDENCY_SHADOWS,
        "the bumped ${entry.identity} changed a package a later classpath entry shares: $why",
    )
}
