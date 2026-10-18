package io.github.zeuspizza.yoriwake.gradle.change

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
     */
    fun walk(
        classpath: Collection<File>,
        buildDirs: Collection<String>,
        rootDir: File,
        maxFiles: Int = MAX_FILES,
        classes: Boolean = false,
    ): Walk {
        val roots = buildDirs.map { File(it).absoluteFile } + rootDir.absoluteFile
        // Gradle's and the plugin's own state, such as the agent jar, is not the build's output.
        val state = File(rootDir.absoluteFile, ".gradle").path + File.separator
        val produced = classpath.map(File::getAbsoluteFile).distinct().filter { entry ->
            roots.any { root -> entry.path.startsWith(root.path + File.separator) } && !entry.path.startsWith(state)
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
                            digests[pathOf(rootDir, file)] =
                                runCatching { file.inputStream().use(::sha256) }.getOrDefault(UNREADABLE)
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
                                digests[key] =
                                    runCatching { zip.getInputStream(zipped).use(::sha256) }.getOrDefault(UNREADABLE)
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
                    digests[pathOf(rootDir, entry)] =
                        runCatching { entry.inputStream().use(::sha256) }.getOrDefault(UNREADABLE)
                }
                // An entry that does not exist (a resource directory with nothing in it) holds nothing.
            }
        }
        return Walk.Found(digests)
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
         * - a class file, read as a class rather than a file;
         * - one outside the root, whose reads are not recorded;
         * - one whose path holds a comma, which the selector's list cannot carry;
         * - a manifest whose loaded attributes moved or cannot be told.
         */
        fun traceable(): List<String>? {
            val handed = mutableListOf<String>()
            for ((path, how) in files) {
                if (how != "changed" || path.endsWith(EXPOSED_SUFFIX) || path.endsWith(".class")) return null
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

    /** Gradle's module cache, whose paths name their content. */
    private fun inModuleCache(file: File) = "/caches/modules-2/" in file.invariantSeparatorsPath

    private fun budgetSpent(maxFiles: Int) =
        Walk.Refused("the build-produced files on the classpath exceed the $maxFiles-file budget")

    private fun pathOf(rootDir: File, file: File): String =
        runCatching { rootDir.absoluteFile.toPath().relativize(file.absoluteFile.toPath()).toString() }
            .getOrDefault(file.absolutePath)
            .replace(File.separatorChar, '/')

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
}
