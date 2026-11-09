package io.github.zeuspizza.yoriwake.gradle.change

import io.github.zeuspizza.yoriwake.agent.contract.Tsv
import io.github.zeuspizza.yoriwake.gradle.capture.writeAtomically
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * A test task's resolved runtime classpath, in order, with which entries are library modules: what a
 * dependency bump is judged against. A dating capture writes it beside the map ([FILE] and
 * [DIRS_FILE]); a selecting run computes it again and compares.
 *
 * Each entry is keyed as [ClasspathFiles.table] keys it, so another checkout or machine names it
 * alike. A module is named by its identity (`group:name`, plus classifier and extension when not a
 * plain jar), wherever its jar lies: a file repository inside the checkout holds modules too. For
 * each module jar, every directory's entry names are digested, so a name a new version adds is seen
 * without the old jar.
 */
internal object ClasspathRecord {

    const val FILE = "classpath.tsv"
    const val DIRS_FILE = "classpath-dirs.tsv"

    /** Where a capturing run hands the record to the decode, as the classpath files are. */
    fun pending(mapDir: File) = File(mapDir, "$FILE.pending")

    fun pendingDirs(mapDir: File) = File(mapDir, "$DIRS_FILE.pending")

    /**
     * The module jars of the classpath the test JVM starts with, `path \t identity`, for the agent to
     * tell their reads apart and the decode to name them.
     */
    const val MODULES_FILE = "jar-modules.tsv"

    const val MODULE = "module"
    const val PROJECT = "project"
    const val OTHER = "file"

    /** What a module whose path names its content records as its digest. */
    const val NAMED = "named"

    data class Entry(
        val kind: String,
        /** A module's identity; any other entry's key. */
        val identity: String,
        val key: String,
        /** A module's content, [NAMED] when [key] names it; `-` for any other entry. */
        val digest: String,
        /** Where it is on this machine; never written. */
        val file: File? = null,
    )

    data class Record(
        val entries: List<Entry>,
        /** Module identity to directory (`a/b/`, `/` for the jar's root) to the digest of its entry names. */
        val dirs: Map<String, Map<String, String>>,
    ) {
        val modules: List<Entry> get() = entries.filter { it.kind == MODULE }
    }

    /**
     * The record of [classpath], whose module files [modules] names with their identities. A module
     * jar that cannot be listed digests every directory as unreadable, which never equals anything.
     */
    fun of(
        classpath: Collection<File>,
        modules: Map<File, String>,
        buildDirs: Collection<String>,
        rootDir: File,
        homes: Map<String, File>,
    ): Record {
        val identities = modules.mapKeys { it.key.absoluteFile }
        val entries = ClasspathFiles.keys(classpath, buildDirs, rootDir, homes).map { (file, key, read) ->
            val identity = identities[file]
            when {
                identity != null -> Entry(MODULE, identity, key, if (read == ClasspathFiles.Read.NAMED) NAMED else sha256(file), file)
                read == ClasspathFiles.Read.BUILT -> Entry(PROJECT, key, key, "-", file)
                else -> Entry(OTHER, key, key, "-", file)
            }
        }
        val dirs = entries.filter { it.kind == MODULE }.associate { it.identity to directories(it.file!!) }
        return Record(entries, dirs)
    }

    /**
     * The key under which [directories] digests what the JDK's class loader takes from the jar's
     * manifest when it opens the jar: its `Class-Path` and `Multi-Release`. Never a directory.
     */
    const val MANIFEST = "#manifest"

    /**
     * Each directory of the jar at [file] with the digest of the names directly in it, its
     * subdirectories' names (`b/`) included, so a lookup of `a/b` or a scan of `a/` sees a new
     * subdirectory; and [MANIFEST]. A file that is not a readable jar digests as unreadable.
     */
    fun directories(file: File): Map<String, String> {
        if (!file.isFile) return mapOf("/" to ClasspathFiles.UNREADABLE)
        val read = runCatching {
            java.util.jar.JarFile(file, false).use { jar ->
                val names = jar.entries().asSequence().filterNot { it.isDirectory }.map { it.name }.toList()
                val attributes = jar.manifest?.mainAttributes
                names to listOf("Class-Path", "Multi-Release").joinToString("\n") { "$it=${attributes?.getValue(it).orEmpty()}" }
            }
        }.getOrElse { return mapOf("/" to ClasspathFiles.UNREADABLE) }
        val (names, manifest) = read
        val listed = sortedMapOf<String, MutableSet<String>>()
        for (name in names) {
            var child = name
            var parent = parentOf(name)
            listed.getOrPut(parent) { sortedSetOf() }.add(child.substringAfterLast('/').ifEmpty { child })
            // Each directory above names the one below it.
            while (parent != "/") {
                child = parent
                parent = parentOf(parent.removeSuffix("/"))
                listed.getOrPut(parent) { sortedSetOf() }.add(child.removeSuffix("/").substringAfterLast('/') + "/")
            }
        }
        val digests = listed.mapValuesTo(sortedMapOf()) { (_, inDir) -> digest(inDir.joinToString("\n")).take(16) }
        digests[MANIFEST] = digest(manifest).take(16)
        return digests
    }

    /** The directory holding [name], `a/b/` or `/` for the jar's root. */
    private fun parentOf(name: String) = name.substringBeforeLast('/', "").let { if (it.isEmpty()) "/" else "$it/" }

    fun write(entriesFile: File, dirsFile: File, record: Record) {
        writeAtomically(entriesFile, record.entries.joinToString("") { Tsv.join(it.kind, it.identity, it.key, it.digest) + "\n" })
        writeAtomically(
            dirsFile,
            record.dirs.entries.sortedBy { it.key }.flatMap { (identity, dirs) ->
                dirs.map { (dir, digest) -> Tsv.join(identity, dir, digest) + "\n" }
            }.joinToString(""),
        )
    }

    /** The record [write] wrote, or null when either file is missing, torn or malformed. */
    fun read(entriesFile: File, dirsFile: File): Record? = runCatching {
        if (!entriesFile.isFile || !dirsFile.isFile) return null
        val entryText = entriesFile.readText()
        val dirText = dirsFile.readText()
        if ((entryText.isNotEmpty() && !entryText.endsWith("\n")) || (dirText.isNotEmpty() && !dirText.endsWith("\n"))) return null
        val entries = entryText.lines().filter(String::isNotEmpty).map { line ->
            val parts = Tsv.split(line)
            if (parts.size != 4) return null
            Entry(parts[0], parts[1], parts[2], parts[3])
        }
        val dirs = LinkedHashMap<String, MutableMap<String, String>>()
        for (line in dirText.lines().filter(String::isNotEmpty)) {
            val parts = Tsv.split(line)
            if (parts.size != 3) return null
            dirs.getOrPut(parts[0]) { sortedMapOf() }[parts[1]] = parts[2]
        }
        Record(entries, dirs)
    }.getOrNull()

    fun readMap(mapDir: File): Record? = read(File(mapDir, FILE), File(mapDir, DIRS_FILE))

    /** Writes [MODULES_FILE] for the agent and the decode. */
    fun writeModules(file: File, record: Record) {
        writeAtomically(file, record.modules.joinToString("") { Tsv.join(it.file!!.absolutePath, it.identity) + "\n" })
    }

    /** [MODULES_FILE] as absolute path to identity; null when it cannot be read. */
    fun readModules(file: File): Map<String, String>? = runCatching {
        if (!file.isFile) return null
        file.readLines().filter(String::isNotEmpty).associate { line ->
            val parts = Tsv.split(line)
            if (parts.size != 2) return null
            parts[0] to parts[1]
        }
    }.getOrNull()

    private fun sha256(file: File): String = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrDefault(ClasspathFiles.UNREADABLE)

    private fun digest(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
