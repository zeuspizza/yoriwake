package io.github.zeuspizza.yoriwake.gradle.change

import io.github.zeuspizza.yoriwake.agent.capture.ZipContent
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.util.jar.Attributes
import java.util.jar.JarFile
import java.util.jar.Manifest

/**
 * The non-class files the build produced onto a test task's runtime classpath, by content.
 *
 * Change detection leaves build output out: a change reaches it through the tracked sources that
 * produce it. That fails for a file generated from git state, a property or the clock, so its
 * content is recorded at each dating capture and compared before a selecting run's tests start.
 * Class files are the class-digest rule's.
 *
 * The same table names each jar or directory the classpath takes from outside the build ([table]):
 * a dependency that resolves to another jar changes what the tests run against with no changed path
 * behind it, whatever moved it (a properties file, a `-P` or `-D` property, a dynamic version).
 */
internal object ClasspathFiles {

    /** What a file that could not be read digests to: never equal to anything, itself included. */
    const val UNREADABLE = "unreadable"

    /**
     * Appended to a jar manifest's path for the digest of what the JDK's class loader takes from
     * it ([LOADER_EXPOSED]); [UNREADABLE] when the manifest does not parse.
     */
    const val EXPOSED_SUFFIX = "#loader-exposed"

    /**
     * The manifest attributes the JDK's class loader acts on or hands to code: the class path and
     * multi-release flag it opens the jar with, the module name it gives the jar on the module
     * path, and what a package defined from the jar reports.
     */
    private val LOADER_EXPOSED = listOf(
        "Class-Path", "Multi-Release", "Automatic-Module-Name",
        "Specification-Title", "Specification-Version", "Specification-Vendor",
        "Implementation-Title", "Implementation-Version", "Implementation-Vendor", "Sealed",
    ).map(Attributes::Name)

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
     * `<jar>!/<entry>`. With [classes], class files are walked too.
     *
     * What a walked jar's manifest `Class-Path` names is on the test JVM's class path too, and on no
     * classpath Gradle lists: it is walked wherever it lies, but in Gradle's module cache, whose paths
     * name their content, and with its class files, which the class-digest rule never reads.
     *
     * A copy of a tracked text file that git writes with `\r\n` in this checkout is digested with
     * its line endings as `\n` ([TrackedText]), so a checkout with other line-ending settings
     * records the same digest. Every other file is digested by its bytes.
     */
    fun walk(
        classpath: Collection<File>,
        buildDirs: Collection<String>,
        rootDir: File,
        maxFiles: Int = MAX_FILES,
        classes: Boolean = false,
        text: TrackedText = TrackedText(rootDir),
        dependency: (File) -> Boolean = { false },
    ): Walk {
        val roots = buildDirs.map { File(it).absoluteFile } + rootDir.absoluteFile
        // Gradle's and the plugin's own state, such as the agent jar, is not the build's output.
        val state = File(rootDir.absoluteFile, ".gradle").path + File.separator
        val produced = classpath.map(File::getAbsoluteFile).distinct().filter { entry ->
            roots.any { root -> entry.path.startsWith(root.path + File.separator) } && !entry.path.startsWith(state) &&
                !dependency(entry)
        }
        val digests = sortedMapOf<String, String>()
        var files = 0
        // Each entry with whether its class files are walked; what a manifest names joins at the end.
        val pending = ArrayDeque(produced.map { it to classes })
        val seen = classpath.mapTo(mutableSetOf()) { it.absoluteFile.normalize() }
        while (pending.isNotEmpty()) {
            val (entry, withClasses) = pending.removeFirst()
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
                            if (!withClasses && file.name.endsWith(".class")) continue
                            if (++files > maxFiles) return budgetSpent(maxFiles)
                            digests[pathOf(rootDir, file)] = runCatching {
                                digestOf(file::inputStream, pathOf(entry, file), text)
                            }.getOrDefault(UNREADABLE)
                        }
                    }
                }
                entry.isFile && (entry.name.endsWith(".jar") || entry.name.endsWith(".zip")) -> {
                    val jar = pathOf(rootDir, entry)
                    val read = runCatching {
                        JarFile(entry, false).use { zip ->
                            for (zipped in zip.entries()) {
                                if (zipped.isDirectory || (!withClasses && zipped.name.endsWith(".class"))) continue
                                if (++files > maxFiles) return budgetSpent(maxFiles)
                                val key = "$jar!/${zipped.name}"
                                if (zipped.name == JarFile.MANIFEST_NAME) {
                                    val bytes = runCatching { zip.getInputStream(zipped).use { it.readBytes() } }.getOrNull()
                                    digests[key] = bytes?.let { sha256(ByteArrayInputStream(it)) } ?: UNREADABLE
                                    digests[key + EXPOSED_SUFFIX] = bytes?.let(::exposed) ?: UNREADABLE
                                    continue
                                }
                                digests[key] = runCatching {
                                    digestOf({ zip.getInputStream(zipped) }, zipped.name, text)
                                }.getOrDefault(UNREADABLE)
                            }
                            val classPath = zip.manifest?.mainAttributes?.getValue(Attributes.Name.CLASS_PATH).orEmpty()
                            for (token in classPath.split(' ', '\t', '\n', '\r', '\u000c').filter(String::isNotEmpty)) {
                                val uri = runCatching { entry.toURI().resolve(token) }.getOrNull()
                                // The JVM ignores a URL that is not a `file:` one.
                                if (uri != null && !uri.scheme.equals("file", ignoreCase = true)) continue
                                val named = uri?.let { runCatching { File(it).absoluteFile.normalize() }.getOrNull() }
                                if (named == null) {
                                    // Not resolved here, so it forces rather than guesses.
                                    digests["$jar Class-Path $token"] = UNREADABLE
                                } else if (!inModuleCache(named) && seen.add(named)) {
                                    pending.addLast(named to true)
                                }
                            }
                        }
                    }
                    if (read.isFailure) digests[jar] = UNREADABLE
                }
                entry.isFile -> {
                    if (++files > maxFiles) return budgetSpent(maxFiles)
                    digests[pathOf(rootDir, entry)] = runCatching {
                        digestOf(entry::inputStream, entry.name, text)
                    }.getOrDefault(UNREADABLE)
                }
                // An entry that does not exist (a resource directory with nothing in it) holds nothing.
            }
        }
        return Walk.Found(digests)
    }

    /**
     * [walk]'s digests plus one row per classpath entry from outside the build, keyed
     * `dependency:<where>/<path>` so that another checkout or machine names it alike, and
     * [DEPENDENCY_ORDER], a digest of every entry's key in classpath order. [homes] maps a
     * placeholder ([GRADLE_USER_HOME], [PROJECT_CACHE_DIR], [MAVEN_REPOSITORY], [USER_HOME]) to its
     * directory. An entry under one is a dependency, a local repository inside the checkout included,
     * unless a root or build directory holding it lies deeper, as a checkout in the user's home does.
     *
     * Under the Gradle user home, the module cache, generated Gradle API jars and wrapper
     * distributions are named by their path, which names their content or Gradle's version, and
     * never read; so is what the plugin itself writes under [PROJECT_CACHE_DIR]. An entry under the user's home or under no home, such as the jar of a build this
     * one includes or an absolute `files(...)`, is read by its content: a jar entry by entry (a
     * rebuilt jar keeps entry timestamps), a directory file by file. Every other entry under a home
     * is read whole. An entry under no home is keyed `dependency:<outside>/<name>`, with `#2`, `#3`
     * for a name repeated in classpath order.
     */
    fun table(
        classpath: Collection<File>,
        buildDirs: Collection<String>,
        rootDir: File,
        homes: Map<String, File>,
        maxFiles: Int = MAX_FILES,
        text: TrackedText = TrackedText(rootDir),
    ): Walk {
        val root = rootDir.absoluteFile
        val roots = buildDirs.map { File(it).absoluteFile } + root
        val state = File(root, ".gradle")
        fun File.holds(entry: File) = entry == this || entry.path.startsWith(path + File.separator)
        // The deepest first, so a home inside another (a Gradle user home under the user's) wins.
        val byDepth = homes.mapValues { it.value.absoluteFile }.entries.sortedByDescending { it.value.path.length }
        // A home counts only where it lies deeper than the root or build directory holding the entry:
        // a local repository inside the checkout holds dependencies, a checkout inside the user's home
        // holds build output.
        fun homeOf(entry: File) = byDepth.firstOrNull { (_, home) -> home.holds(entry) && home != entry }
            ?.takeIf { (_, home) -> roots.none { it.holds(entry) && it.path.length >= home.path.length } }
        val rows = sortedMapOf<String, String>()
        val order = mutableListOf<String>()
        val repeated = mutableMapOf<String, Int>()
        for (entry in classpath.map(File::getAbsoluteFile).distinct()) {
            val home = homeOf(entry)
            val built = home == null && !state.holds(entry) && roots.any { it.holds(entry) }
            if (built) {
                order += pathOf(rootDir, entry)
                continue
            }
            val key = if (home != null) {
                val relative = entry.relativeTo(home.value).invariantSeparatorsPath
                val digest = when {
                    home.key == GRADLE_USER_HOME && NAMED_LAYOUTS.any { it.containsMatchIn(relative) } -> NAMED_BY_PATH
                    // The plugin's own agent jar, written from the plugin's bytes by a `doFirst` that may
                    // run after this: read, it would be absent at one end and present at the other.
                    home.key == PROJECT_CACHE_DIR -> NAMED_BY_PATH
                    // Where other builds live, such as one this build includes, whose jar is rebuilt.
                    home.key == USER_HOME -> contentDigest(entry)
                    else -> wholeDigest(entry)
                }
                "$DEPENDENCY_PREFIX${home.key}/$relative".also { rows[it] = digest }
            } else {
                val count = repeated.merge(entry.name, 1, Int::plus)!!
                val suffix = if (count > 1) "#$count" else ""
                "$DEPENDENCY_PREFIX$OUTSIDE/${entry.name}$suffix".also { rows[it] = contentDigest(entry) }
            }
            order += key
        }
        rows[DEPENDENCY_ORDER] = sha256(ByteArrayInputStream(order.joinToString("\n").toByteArray()))
        return when (val walk = walk(classpath, buildDirs, rootDir, maxFiles, text = text, dependency = { homeOf(it) != null })) {
            is Walk.Refused -> walk
            is Walk.Found -> Walk.Found((walk.digests + rows).toSortedMap())
        }
    }

    /** What moved since the capture, each file with how; empty when nothing did. */
    data class Moved(val files: Map<String, String>) {
        val isEmpty: Boolean get() = files.isEmpty()

        /**
         * The moved files whose readers a capture records, when every move is one: a non-class file
         * beneath the root whose content changed and, for a jar's manifest, nothing the class
         * loader takes from it. Null when any move is not:
         * - a file that appeared or is gone, which is seen without a read;
         * - one that cannot be read;
         * - a class or source file, read as a class rather than a file;
         * - an archive inside a directory, compared whole but read by its entries;
         * - one outside the root, whose reads are not recorded;
         * - one whose path holds a comma, which the selector's list cannot carry;
         * - a manifest whose loaded attributes moved or cannot be told.
         */
        fun traceable(rootDir: File): List<String>? {
            val handed = mutableListOf<String>()
            for ((path, how) in files) {
                if (path.startsWith(DEPENDENCY_PREFIX)) return null
                if (how != "changed" || path.endsWith(EXPOSED_SUFFIX) || path.endsWith(".class")) return null
                if (SOURCE_SUFFIXES.any(path::endsWith)) return null
                if ("!/" !in path && isArchive(File(rootDir, path))) return null
                if (path.startsWith("../") || File(path).isAbsolute) return null
                // The selector is handed a comma-separated list.
                if (',' in path) return null
                handed += path
            }
            return handed
        }

        /** Up to five files with how each moved, and how many more. */
        fun named(): String {
            // A manifest's loaded attributes are shown on the manifest, unless the manifest is too.
            val real = files.filterKeys { !it.endsWith(EXPOSED_SUFFIX) || it.removeSuffix(EXPOSED_SUFFIX) !in files }
            val shown = real.entries.take(NAMED).joinToString(", ") { (path, how) ->
                if (path.endsWith(EXPOSED_SUFFIX)) "${path.removeSuffix(EXPOSED_SUFFIX)} (its loaded attributes $how)"
                else "$path ($how)"
            }
            return if (real.size > NAMED) "$shown and ${real.size - NAMED} more" else shown
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

    /**
     * A digest of [LOADER_EXPOSED] in the main section and every per-entry section, in a fixed
     * order; [UNREADABLE] when the manifest does not parse.
     */
    private fun exposed(bytes: ByteArray): String = runCatching {
        val manifest = Manifest(ByteArrayInputStream(bytes))
        val sections = listOf("" to manifest.mainAttributes) + manifest.entries.entries.sortedBy { it.key }.map { it.key to it.value }
        val text = buildString {
            for ((section, attributes) in sections) {
                for (name in LOADER_EXPOSED) {
                    val value = attributes.getValue(name) ?: continue
                    append(section).append('\u0000').append(name).append('\u0000').append(value).append('\n')
                }
            }
        }
        sha256(ByteArrayInputStream(text.toByteArray()))
    }.getOrDefault(UNREADABLE)

    /**
     * The key [walk] gives a file a test JVM read, by its absolute path or as `<jar>!/<entry>`:
     * relative to [rootDir] as given and, failing that, as both resolve. Null when it is not
     * beneath the root.
     */
    fun keyOf(rootDir: File, read: String): String? {
        val bang = read.indexOf("!/")
        val file = File(if (bang < 0) read else read.substring(0, bang))
        val relative = beneath(rootDir, file) ?: return null
        return if (bang < 0) relative else relative + read.substring(bang)
    }

    private fun beneath(rootDir: File, file: File): String? {
        val given = runCatching {
            rootDir.absoluteFile.toPath().normalize().relativize(file.absoluteFile.toPath().normalize()).toString()
        }.getOrNull()
        val relative = given?.takeUnless { it.startsWith("..") } ?: runCatching {
            rootDir.toPath().toRealPath().relativize(file.toPath().toRealPath()).toString()
        }.getOrNull()?.takeUnless { it.startsWith("..") }
        return relative?.replace(File.separatorChar, '/')
    }

    /** What the agent records a read of as a class's, never as a file's. */
    private val SOURCE_SUFFIXES = listOf(".java", ".kt", ".groovy", ".scala")

    /**
     * An archive by its name, by its first bytes, or by the content test the agent records reads by;
     * one that cannot be read counts as one. What the agent takes for an archive it records as entry
     * reads, which never name the file, so such a file must not be handed over as a plain one.
     */
    private fun isArchive(file: File): Boolean {
        val name = file.name
        if (ARCHIVE_SUFFIXES.any(name::endsWith)) return true
        if (ZipContent.holdsAZip(file)) return true
        return runCatching {
            file.inputStream().use { input ->
                val head = input.readNBytes(2)
                head.size == 2 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()
            }
        }.getOrDefault(true)
    }

    private val ARCHIVE_SUFFIXES = listOf(".jar", ".zip", ".war", ".ear", ".aar", ".jmod")

    /** The prefix of every row [table] adds; no path relative to the root starts with it. */
    const val DEPENDENCY_PREFIX = "dependency:"

    /** The row digesting the classpath's order; a table without it was recorded before dependency rows were. */
    const val DEPENDENCY_ORDER = DEPENDENCY_PREFIX + "#order"

    const val GRADLE_USER_HOME = "<gradle-user-home>"
    const val PROJECT_CACHE_DIR = "<project-cache-dir>"
    const val MAVEN_REPOSITORY = "<maven-repository>"
    const val USER_HOME = "<home>"
    private const val OUTSIDE = "<outside>"

    /** What a row whose path names its content records, having read nothing. */
    private const val NAMED_BY_PATH = "named"

    /** What a classpath entry that does not exist records. */
    private const val ABSENT = "absent"

    /**
     * Layouts under the Gradle user home whose paths name what they hold: the module cache by each
     * file's SHA-1, the others by Gradle's version.
     */
    private val NAMED_LAYOUTS = listOf(
        Regex("^caches/modules-2/"),
        Regex("^caches/[^/]+/generated-gradle-jars/"),
        Regex("^wrapper/dists/"),
    )

    /** A file's bytes, or a directory's files by relative path and bytes; [ABSENT] when neither. */
    private fun wholeDigest(entry: File): String = runCatching {
        when {
            entry.isFile -> entry.inputStream().use(::sha256)
            entry.isDirectory -> directoryDigest(entry)
            else -> ABSENT
        }
    }.getOrDefault(UNREADABLE)

    /** As [wholeDigest], but a jar by each entry's name and bytes, never its entry timestamps. */
    private fun contentDigest(entry: File): String {
        if (!entry.isFile || !(entry.name.endsWith(".jar") || entry.name.endsWith(".zip"))) return wholeDigest(entry)
        return runCatching {
            JarFile(entry, false).use { zip ->
                val lines = zip.entries().asSequence().filterNot { it.isDirectory }.sortedBy { it.name }
                    .map { "${it.name}\u0000${zip.getInputStream(it).use(::sha256)}" }.toList()
                sha256(ByteArrayInputStream(lines.joinToString("\n").toByteArray()))
            }
        }.getOrDefault(UNREADABLE)
    }

    private fun directoryDigest(directory: File): String {
        val lines = directory.walkTopDown().filter { it.isFile }
            .map { it.relativeTo(directory).invariantSeparatorsPath to it }
            .sortedBy { it.first }
            .map { (relative, file) -> "$relative\u0000${file.inputStream().use(::sha256)}" }.toList()
        return sha256(ByteArrayInputStream(lines.joinToString("\n").toByteArray()))
    }

    /** Gradle's module cache, whose paths name their content. */
    private fun inModuleCache(file: File) = "/caches/modules-2/" in file.invariantSeparatorsPath

    private fun budgetSpent(maxFiles: Int) =
        Walk.Refused("the build-produced files on the classpath exceed the $maxFiles-file budget")

    private fun pathOf(rootDir: File, file: File): String =
        runCatching { rootDir.absoluteFile.toPath().relativize(file.absoluteFile.toPath()).toString() }
            .getOrDefault(file.absolutePath)
            .replace(File.separatorChar, '/')

    /**
     * The digest of what [open] reads: its `\n` form when [text] vouches for it as a copy of a
     * tracked file at a path ending with [relative], else its bytes. A class file, or one too large
     * to hold, is only ever digested by its bytes.
     */
    private fun digestOf(open: () -> java.io.InputStream, relative: String, text: TrackedText): String {
        if (relative.endsWith(".class")) return open().use(::sha256)
        val bytes = open().use { it.readNBytes(TEXT_CAP + 1) }
        if (bytes.size > TEXT_CAP) return open().use(::sha256)
        // A lookup that fails only costs the exemption, never the file's digest.
        return runCatching { text.digest(bytes, relative) }.getOrNull() ?: sha256(ByteArrayInputStream(bytes))
    }

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

    /** The largest file read whole to look for line endings to normalise. */
    private const val TEXT_CAP = 8 * 1024 * 1024
}
