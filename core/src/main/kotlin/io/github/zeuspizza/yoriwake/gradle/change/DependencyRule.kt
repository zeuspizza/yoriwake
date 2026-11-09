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
 * - a version key a plugin shares, a library alias build logic names, a version read as a value.
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
        if (inputs.context.maven != null) {
            pomReads(inputs, inputs.context.maven, bumps, propertyReaders)?.let { return it }
        } else {
            buildLogicReads(inputs, bumps, catalogs, includedBuilds)?.let { return it }
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
        located(inputs, bumps, catalogs, propertyReaders, recorded, current)?.let { return it }
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
        recorded: ClasspathRecord.Record,
        current: ClasspathRecord.Record,
    ): Verdict? {
        val onClasspath = (recorded.modules + current.modules).mapTo(HashSet()) { coordinateOf(it.identity) }
        for (bump in bumps) {
            val coordinates = when {
                bump.coordinate == "?" -> return unlocated("a dependency bumped in ${bump.path} names no literal `groupId` and `artifactId`")
                bump.coordinate != null -> listOf(bump.coordinate)
                bump.versionKey != null && bump.shape == CoordinateDiff.Shape.POM -> propertyReaders[bump.versionKey].orEmpty()
                bump.versionKey != null -> catalogs.values.flatMap { librariesUsing(it, bump.versionKey).values }
                    .map { it ?: return unlocated("the version `${bump.versionKey}` is used by a library whose coordinate cannot be read") }
                else -> emptyList()
            }
            for (coordinate in coordinates) {
                if (coordinate !in onClasspath) {
                    return unlocated("`$coordinate`, bumped in ${bump.path}, is on none of this task's classpaths")
                }
            }
            val configuration = bump.configuration ?: continue
            val configurations = runCatching { inputs.context.configurations() }.getOrNull()
            if (configurations == null || configuration !in configurations) {
                return unlocated("`${bump.coordinate}`, bumped in ${bump.path}, is declared in `$configuration`, which this task's classpath does not extend")
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
