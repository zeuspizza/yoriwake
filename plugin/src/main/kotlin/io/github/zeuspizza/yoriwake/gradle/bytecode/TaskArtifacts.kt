package io.github.zeuspizza.yoriwake.gradle.bytecode

import java.io.File
import java.util.Optional
import java.util.zip.ZipFile

/** What the inline scan found, or why it refused to answer. A refusal always names its cause. */
internal sealed interface InlineScan {
    /** The classes that inline one of the changed sources. Empty means none, and is a fact. */
    data class Found(val classes: Set<String>) : InlineScan

    /**
     * The scan did not finish, so nothing may be deselected on its strength. [kind] says which
     * remedy could help; [reason] names the file, directory or build it happened to.
     */
    data class Refused(val reason: String, val kind: Kind) : InlineScan

    /** Which refusal this is, in a form nothing has to substring-match English to read. */
    enum class Kind {
        /** There is no own output directory and no own jar: the scan could not start. */
        NOTHING_TO_SCAN,

        /** A classpath directory holds classes and is nobody's module output, so it was not walked. */
        UNACCOUNTED_OUTPUT,

        /** A class, entry or jar could not be read, was over a cap, or the file budget ran out. */
        INCOMPLETE,

        /** The walk completed, but the output holds Kotlin and no class carries an SMAP. */
        SMAP_ABSENT,

        /**
         * The classpath could not be resolved, so the scan never began. A fact about the caller:
         * Gradle will not resolve an entry mapped from a transform task (Android unit tests) before
         * that task has run, and `yoriwakeAudit` runs no tasks.
         */
        CLASSPATH_UNRESOLVED,
    }
}

/**
 * Runs an inline scan and says whether resolving the classpath or scanning it failed: the two have
 * unrelated remedies, and a selecting build resolves the classpath only after its producers ran.
 */
internal fun scanOrExplain(
    resolve: () -> TaskArtifacts,
    scan: (TaskArtifacts) -> InlineScan,
): InlineScan = runCatching(resolve).fold(
    onSuccess = { artifacts ->
        runCatching { scan(artifacts) }.getOrElse { problem ->
            InlineScan.Refused(
                "the scan threw ${problem::class.java.name}" +
                    (problem.message?.let { ": $it" } ?: " with no message"),
                InlineScan.Kind.INCOMPLETE,
            )
        }
    },
    onFailure = { problem ->
        InlineScan.Refused(
            "the classpath could not be resolved here: ${problem::class.java.name}" +
                (problem.message?.let { ": $it" } ?: " with no message"),
            InlineScan.Kind.CLASSPATH_UNRESOLVED,
        )
    },
)

/**
 * Why a bounded walk over this build's own classes stopped. [budgetSpent] is separate because a
 * budget is a number somebody can raise, and everything else is a file somebody has to look at.
 */
internal data class WalkStop(val reason: String, val budgetSpent: Boolean = false)

/**
 * Every class of this build's own output, digested -- or why the walk could not finish. Kept apart
 * from an empty map: reading a class absent from an unfinished walk as "unchanged" would narrow.
 */
internal sealed interface DigestScan {
    /** Complete. Every class of the own output is in [digests]. */
    data class Found(val digests: Map<String, String>, val counts: Counts) : DigestScan

    /** Incomplete, so nothing may be concluded from a name's presence or absence in it. */
    data class Refused(val reason: String, val kind: Kind, val counts: Counts) : DigestScan

    enum class Kind {
        /** There is no own output directory and no own jar: the walk could not start. */
        NOTHING_TO_SCAN,

        /** A classpath directory holds classes and is nobody's module output, so it was not walked. */
        UNACCOUNTED_OUTPUT,

        /** A class file could not be read, or its bytecode could not be understood. */
        UNREADABLE,

        /** The file budget ran out, so an unknown number of classes were never reached. */
        BUDGET_SPENT,
    }

    /** What the walk did before it stopped. No budget-skipped count: nobody knows that number. */
    data class Counts(val considered: Int = 0, val digested: Int = 0, val unreadable: Int = 0)
}

/**
 * The compiled classes and readable resources of a test task, as files on disk.
 *
 * Everything here answers null or false rather than throwing. This runs inside someone else's build,
 * and a question it cannot answer must degrade into a full run, never into a failure.
 */
internal class TaskArtifacts(
    classpath: Collection<File>,
    private val moduleBuildDirs: Collection<String>,
    /** This task's own compiled test classes, from `Test.testClassesDirs`. */
    private val testOutputs: Collection<File> = emptyList(),
    /** The file budget the own-classes walk stops at; a parameter so tests can make it fire. */
    private val maxFiles: Int = MAX_FILES,
) {

    private val directories = classpath.filter(File::isDirectory)

    private val jars = classpath.filter { it.isFile && it.name.endsWith(".jar") }

    private val ownJars = jars.filter { file ->
        moduleBuildDirs.any { file.absolutePath.startsWith(it + File.separator) }
    }

    // Dependencies can name an application class too (a service file, a Jackson registration).
    // They are read under MAX_SCAN_BYTES, and anything not found when it runs out counts as named.
    private val otherJars = jars - ownJars.toSet()

    /**
     * Every compiled class a changed source file produced, nested and file classes included, since
     * the file is judged as a whole. Null when nothing could be searched: a refusal, not "none".
     */
    fun classesFor(prefix: String): List<Pair<String, ByteArray>>? =
        compiled.getOrPut(prefix) { Optional.ofNullable(findClassesFor(prefix)) }.orElse(null)

    /** Memoised: the test-output rule and the absence rule ask about the same prefix. */
    private val compiled = mutableMapOf<String, Optional<List<Pair<String, ByteArray>>>>()

    private fun findClassesFor(prefix: String): List<Pair<String, ByteArray>>? {
        if (directories.isEmpty() && ownJars.isEmpty()) {
            return null
        }
        val binary = prefix.replace('.', '/')
        val packageDir = binary.substringBeforeLast('/', "")
        val simpleName = binary.substringAfterLast('/')
        val found = mutableListOf<Pair<String, ByteArray>>()
        directories.forEach { root ->
            val dir = if (packageDir.isEmpty()) root else File(root, packageDir)
            dir.listFiles()?.forEach { candidate ->
                if (candidate.isFile && candidate.name.endsWith(".class") && declaredBy(candidate.name, simpleName)) {
                    val bytes = runCatching { candidate.readBytes() }.getOrNull() ?: return null
                    val name = (if (packageDir.isEmpty()) "" else packageDir.replace('/', '.') + ".") +
                        candidate.name.removeSuffix(".class")
                    found += name to bytes
                }
            }
        }
        // A module that packages its output as a jar has no classes directory for the walk above.
        ownJars.forEach { jar -> found += classesInJar(jar, packageDir, simpleName) }
        return found
    }


    private fun classesInJar(jar: File, packageDir: String, simpleName: String): List<Pair<String, ByteArray>> =
        runCatching {
            ZipFile(jar).use { zip ->
                zip.entries().asSequence()
                    .filter { entry ->
                        !entry.isDirectory && entry.name.endsWith(".class") &&
                            entry.name.substringBeforeLast('/', "") == packageDir &&
                            declaredBy(entry.name.substringAfterLast('/'), simpleName)
                    }
                    .map { entry ->
                        val name = entry.name.removeSuffix(".class").replace('/', '.')
                        name to zip.getInputStream(entry).use { it.readBytes() }
                    }
                    .toList()
            }
        }.getOrDefault(emptyList())

    /**
     * Which of these repository paths any compiled class on the classpath can name; a test can only
     * read a file it can name. An ancestor counts as a hit, for paths assembled at runtime. Known
     * limit: a test that walks the repository from its root names nothing.
     */
    fun pathsNamedInClasses(paths: Collection<String>): Set<String> {
        if (paths.isEmpty()) {
            return emptySet()
        }
        // Nothing to search means every path is named: an empty answer would license a skip.
        if (directories.isEmpty() && ownJars.isEmpty()) {
            return paths.toSet()
        }
        // A non-ASCII path counts as named: class files hold modified UTF-8, so its bytes vary.
        // `namedInBuildScripts` builds the same ancestor forms: change both or neither.
        val unsearchable = paths.filterTo(mutableSetOf()) { path -> path.any { it.code > 127 } }
        val needlesByPath = (paths - unsearchable).associateWith { path ->
            val forms = mutableSetOf(path, path.substringAfterLast('/'))
            var parent = path.substringBeforeLast('/', "")
            while (parent.isNotEmpty()) {
                forms += parent
                forms += parent.substringAfterLast('/')
                parent = parent.substringBeforeLast('/', "")
            }
            forms.filter { it.isNotEmpty() }.map { it.toByteArray(Charsets.ISO_8859_1) }
        }
        if (needlesByPath.isEmpty()) {
            return unsearchable
        }
        val found = mutableSetOf<String>()
        directories.forEach { root -> searchClasses(root, needlesByPath, found) }
        // Without own jars, a module that packages its classes could vouch for no path.
        ownJars.forEach { jar ->
            searchJarEntries(jar, needlesByPath.keys, found, { it.endsWith(".class") }) { bytes, hits ->
                matchPaths(bytes, needlesByPath, hits)
            }
        }
        return found + unsearchable
    }

    private fun searchClasses(
        root: File,
        needlesByPath: Map<String, List<ByteArray>>,
        found: MutableSet<String>,
    ) {
        val stack = ArrayDeque(listOf(root))
        var visited = 0
        while (stack.isNotEmpty()) {
            if (found.size == needlesByPath.size) return
            val current = stack.removeLast()
            if (visited > MAX_FILES) {
                found += needlesByPath.keys
                return
            }
            val entries = current.listFiles() ?: continue
            // Counted per file, not per directory, or the budget guard almost never fires.
            visited += entries.size
            entries.forEach { entry ->
                when {
                    entry.isDirectory -> stack.addLast(entry)
                    !entry.name.endsWith(".class") -> Unit
                    // Too big to read is not "the name is not in it".
                    entry.length() > MAX_RESOURCE_BYTES -> found += needlesByPath.keys
                    else -> matchPaths(runCatching { entry.readBytes() }.getOrNull(), needlesByPath, found)
                }
            }
        }
    }

    /**
     * Whether any compiled class other than this prefix's own, or any resource, holds its name, in
     * binary or internal form: a reference, a descriptor, an annotation value or a string handed to
     * `Class.forName`. Anything that cannot be searched to the end counts as naming it.
     */
    fun namedOutsideItself(prefix: String): Boolean {
        val own = classesFor(prefix)?.mapTo(mutableSetOf()) { (name, _) -> name.replace('.', '/') + ".class" }
            ?: return true
        val internal = prefix.replace('.', '/')
        val needles = listOf(internal, prefix).map { it.toByteArray(Charsets.ISO_8859_1) }
        if (prefix.any { it.code > 127 }) return true
        var visited = 0
        for (root in directories) {
            val stack = ArrayDeque(listOf(root))
            while (stack.isNotEmpty()) {
                val current = stack.removeLast()
                val entries = current.listFiles() ?: continue
                visited += entries.size
                if (visited > maxFiles) return true
                for (entry in entries) {
                    when {
                        entry.isDirectory -> stack.addLast(entry)
                        !entry.name.endsWith(".class") -> Unit
                        entry.relativeTo(root).invariantSeparatorsPath in own -> Unit
                        entry.length() > MAX_RESOURCE_BYTES -> return true
                        else -> {
                            val bytes = runCatching { entry.readBytes() }.getOrNull() ?: return true
                            if (needles.any { contains(bytes, it) }) return true
                        }
                    }
                }
            }
        }
        for (jar in ownJars) {
            val hit = runCatching {
                ZipFile(jar).use { zip ->
                    zip.entries().asSequence().any { entry ->
                        !entry.isDirectory && entry.name.endsWith(".class") && entry.name !in own &&
                            (entry.size > MAX_RESOURCE_BYTES ||
                                zip.getInputStream(entry).use { it.readBytes() }.let { bytes -> needles.any { contains(bytes, it) } })
                    }
                }
            }.getOrElse { true }
            if (hit) return true
        }
        return namedInResources(listOf(prefix, internal)).isNotEmpty()
    }

    /**
     * Whether this task runs code in child JVMs the agent cannot see, which the absence rule would
     * call untested. Only `gradle-testkit` is detected; the general case is a README known limit.
     */
    fun runsCodeInUnobservedJvms(): Boolean =
        jars.any { it.name.startsWith("gradle-testkit") } ||
            directories.any { it.absolutePath.contains("gradle-testkit") }

    /**
     * Whether every class this prefix compiles to lives in this task's own test output. Such a
     * class need not force: a test the map has never seen runs anyway. False when unanswerable.
     */
    fun onlyInTestOutput(classes: List<Pair<String, ByteArray>>?): Boolean {
        if (classes.isNullOrEmpty() || testOutputs.isEmpty()) {
            return false
        }
        // The separator matters: `build/classes/java/test` is a string prefix of
        // `build/classes/java/testFixtures`, whose classes must not count as this task's own.
        val roots = testOutputs.map { it.absolutePath.trimEnd(File.separatorChar) + File.separator }
        return classes.all { (name, _) -> locations(name).any { file -> roots.any(file::startsWith) } }
    }

    private fun locations(className: String): List<String> {
        val relative = className.replace('.', '/') + ".class"
        return directories.map { File(it, relative).absolutePath }
            .filter { File(it).isFile }
    }

    fun testOutputCount(): Int = testOutputs.count(File::isDirectory)

    /**
     * Which of these class names appear as text in any resource this task can read. A hit forces:
     * a `META-INF/services` entry or a YAML naming a class leaves no bytecode or coverage edge.
     * One pass over raw bytes for every name, since decoding every resource can exhaust the daemon.
     */
    fun namedInResources(names: Collection<String>): Set<String> {
        if (names.isEmpty()) {
            return emptySet()
        }
        val needles = names.associateWith { it.toByteArray(Charsets.ISO_8859_1) }
        val found = mutableSetOf<String>()
        directories.forEach { root -> searchTree(root, needles, found) }
        ownJars.forEach { jar -> searchJar(jar, needles, found) }
        val budget = longArrayOf(MAX_SCAN_BYTES)
        otherJars.forEach { jar ->
            if (found.size == needles.size) return found
            if (budget[0] <= 0) {
                found += needles.keys
                return found
            }
            searchJar(jar, needles, found, budget)
        }
        return found
    }

    private fun searchTree(root: File, needles: Map<String, ByteArray>, found: MutableSet<String>) {
        val stack = ArrayDeque(listOf(root))
        var visited = 0
        while (stack.isNotEmpty()) {
            if (found.size == needles.size) return
            val current = stack.removeLast()
            if (visited > MAX_FILES) {
                // An unfinished search that answers "not found" is a silent skip; answer "found".
                found += needles.keys
                return
            }
            val entries = current.listFiles() ?: continue
            // Counted per file, not per directory, or the budget guard almost never fires.
            visited += entries.size
            entries.forEach { entry ->
                when {
                    entry.isDirectory -> stack.addLast(entry)
                    entry.name.endsWith(".class") -> Unit
                    entry.length() > MAX_RESOURCE_BYTES -> found += needles.keys
                    else -> search(runCatching { entry.readBytes() }.getOrNull(), needles, found)
                }
            }
        }
    }

    /** Every path whose name, or an ancestor of it, appears in these bytes. Null bytes are a hit. */
    private fun matchPaths(
        bytes: ByteArray?,
        needlesByPath: Map<String, List<ByteArray>>,
        found: MutableSet<String>,
    ) {
        if (bytes == null) {
            found += needlesByPath.keys
            return
        }
        needlesByPath.forEach { (path, needles) ->
            if (path !in found && needles.any { contains(bytes, it) }) {
                found += path
            }
        }
    }

    private fun searchJar(
        jar: File,
        needles: Map<String, ByteArray>,
        found: MutableSet<String>,
        budget: LongArray? = null,
    ) = searchJarEntries(jar, needles.keys, found, { name -> !name.endsWith(".class") }, budget) { bytes, hits ->
        search(bytes, needles, hits)
    }

    /** Every accepted entry of a jar, searched. An oversized entry or unopenable jar is a hit. */
    private fun searchJarEntries(
        jar: File,
        keys: Set<String>,
        found: MutableSet<String>,
        accept: (String) -> Boolean,
        budget: LongArray? = null,
        scan: (ByteArray?, MutableSet<String>) -> Unit,
    ) {
        runCatching {
            ZipFile(jar).use { zip ->
                for (entry in zip.entries().asSequence()) {
                    if (found.size == keys.size) return
                    if (entry.isDirectory || !accept(entry.name)) continue
                    if (entry.size > MAX_RESOURCE_BYTES) {
                        found += keys
                        continue
                    }
                    if (budget != null) {
                        if (budget[0] <= 0) {
                            found += keys
                            return
                        }
                        budget[0] -= entry.size
                    }
                    scan(zip.getInputStream(entry).use { it.readBytes() }, found)
                }
            }
        }.onFailure {
            found += keys
        }
    }

    /** Null bytes mean a file we could not read, which is a file we cannot rule anything out from. */
    private fun search(bytes: ByteArray?, needles: Map<String, ByteArray>, found: MutableSet<String>) {
        if (bytes == null) {
            found += needles.keys
            return
        }
        needles.forEach { (name, needle) ->
            if (name !in found && contains(bytes, needle)) {
                found += name
            }
        }
    }

    private fun contains(haystack: ByteArray, needle: ByteArray): Boolean {
        if (needle.isEmpty() || needle.size > haystack.size) return false
        val first = needle[0]
        outer@ for (start in 0..haystack.size - needle.size) {
            if (haystack[start] != first) continue
            for (offset in 1 until needle.size) {
                if (haystack[start + offset] != needle[offset]) continue@outer
            }
            return true
        }
        return false
    }

    /** `Foo.class`, `Foo$Inner.class`, `FooKt.class` (Kotlin file class), never `FooBar.class`. */
    private fun declaredBy(fileName: String, simpleName: String): Boolean {
        val name = fileName.removeSuffix(".class")
        return name == simpleName ||
            name == simpleName + "Kt" ||
            name.startsWith("$simpleName$") ||
            // A nested type compiles to `Outer$Name` but the change set names it `pkg.Name`. A name
            // coincidence only pulls more classes into the judgement: a full run, never a skip.
            name.endsWith("$" + simpleName)
    }

    private fun containsInTree(root: File, needle: String): Boolean {
        val stack = ArrayDeque(listOf(root))
        var visited = 0
        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            if (visited > MAX_FILES) {
                // An unfinished search that answers "not found" is a silent skip; answer "found".
                return true
            }
            val entries = current.listFiles() ?: continue
            // Counted per file, not per directory, or the budget guard almost never fires.
            visited += entries.size
            entries.forEach { entry ->
                when {
                    entry.isDirectory -> stack.addLast(entry)
                    entry.name.endsWith(".class") -> Unit
                    entry.length() > MAX_RESOURCE_BYTES -> Unit
                    contains(entry, needle) -> return true
                }
            }
        }
        return false
    }

    private fun contains(file: File, needle: String): Boolean =
        runCatching { file.readText(Charsets.ISO_8859_1).contains(needle) }.getOrElse { true }

    private fun containsInJar(jar: File, needle: String): Boolean = runCatching {
        ZipFile(jar).use { zip ->
            zip.entries().asSequence().any { entry ->
                !entry.isDirectory &&
                    !entry.name.endsWith(".class") &&
                    entry.size <= MAX_RESOURCE_BYTES &&
                    zip.getInputStream(entry).use { stream ->
                        stream.readBytes().toString(Charsets.ISO_8859_1).contains(needle)
                    }
            }
        }
    }.getOrElse { true }


    /**
     * Classes whose bytecode carries code inlined from any of these changed sources -- the edge
     * coverage cannot see. Read from the SMAP Kotlin emits for debuggers; see [InlinedSources].
     *
     * Refuses rather than answering "nothing inlines this" when nothing can be scanned, a class or
     * jar cannot be read, the budget runs out, or a Kotlin output carries no SMAP at all (e.g.
     * `-Xno-source-debug-extension`). A pure Java output does not refuse: Java has no inline bodies.
     */
    internal fun classesInlining(
        prefixes: Collection<String>,
        /** False when no changed source could be inlined, so a missing SMAP is no blind spot. */
        inlinableSourceChanged: Boolean = true,
    ): InlineScan {
        if (prefixes.isEmpty()) return InlineScan.Found(emptySet())
        // Own output only: walking the whole classpath delays the test JVM's start.
        val roots = ownRoots()
        if (roots.isEmpty() && ownJars.isEmpty()) return InlineScan.Refused(
            "there is nothing to scan: no own output directory and no own jar on the classpath",
            InlineScan.Kind.NOTHING_TO_SCAN,
        )
        // Skipped, but not trusted: a non-module directory holding classes could hold a consumer.
        val unwalked = directories.filter { it.isDirectory && it !in roots }
        val unaccounted = unwalked.firstOrNull(::holdsClasses)
        if (unaccounted != null) {
            return InlineScan.Refused(
                "${unaccounted.absolutePath} is on the classpath, holds classes, and is nobody's module output",
                InlineScan.Kind.UNACCOUNTED_OUTPUT,
            )
        }

        val found = mutableSetOf<String>()
        val evidence = SmapEvidence()
        walkOwnClasses(roots) { root, entry ->
            val scan = scanOf(entry)
                ?: return@walkOwnClasses "${entry.absolutePath} could not be read"
            if (!evidence.accept(scan)) {
                // "Not understood" is an unparsable SMAP or ASM rejecting the class file; the
                // version and `diagnose` tell a reader which.
                val bytes = runCatching { entry.readBytes() }.getOrNull()
                return@walkOwnClasses "${entry.absolutePath}" +
                    (bytes?.let { " (class file v${classFileVersion(it)})" } ?: "") +
                    " was not understood: " +
                    (bytes?.let { InlinedSources.diagnose(it) } ?: "its bytes could not be re-read")
            }
            if (matches(scan.inlined, prefixes)) {
                found += classNameOf(root, entry)
            }
            null
        }?.let { return InlineScan.Refused(it.reason, InlineScan.Kind.INCOMPLETE) }
        for (jar in ownJars) {
            val outcome = scanJar(jar, prefixes, found, evidence)
            if (outcome is InlineScan.Refused) return outcome
        }
        // Only when a changed source could be inlined, or a Java project with a few SMAP-less
        // Kotlin classes would refuse on every change.
        if (inlinableSourceChanged && !evidence.usable()) return InlineScan.Refused(evidence.census(), InlineScan.Kind.SMAP_ABSENT)
        return InlineScan.Found(found)
    }

    private fun ownRoots(): List<File> =
        (testOutputs + directories.filter(::isOwnOutput)).filter(File::isDirectory).distinct()

    /**
     * Visits every `.class` under [roots] within the file and size budgets; null when it finished.
     * [visit] answers null to carry on or a reason to stop. An unlistable directory stops the walk.
     */
    private fun walkOwnClasses(roots: List<File>, visit: (root: File, entry: File) -> String?): WalkStop? {
        var visited = 0
        for (root in roots) {
            val stack = ArrayDeque(listOf(root))
            while (stack.isNotEmpty()) {
                val current = stack.removeLast()
                val entries = current.listFiles()
                    ?: return WalkStop("${current.absolutePath} could not be listed")
                visited += entries.size
                if (visited > maxFiles) {
                    return WalkStop("the scan passed its $maxFiles-file budget", budgetSpent = true)
                }
                for (entry in entries) {
                    if (entry.isDirectory) {
                        stack.addLast(entry)
                        continue
                    }
                    if (!entry.name.endsWith(".class")) continue
                    if (entry.length() > MAX_RESOURCE_BYTES) {
                        return WalkStop(
                            "${entry.absolutePath} is larger than the $MAX_RESOURCE_BYTES-byte read cap"
                        )
                    }
                    visit(root, entry)?.let { return WalkStop(it) }
                }
            }
        }
        return null
    }

    /**
     * `className -> constant digest` for own classes that have one, recorded at capture (see
     * CoverageDecoder.CONSTANTS_FILE). Null when the walk could not finish: a partial set would
     * read a missing digest as "no constant", a silent skip.
     */
    fun constantDigests(): Map<String, String>? {
        // Own jars too: [classesFor] reads them, and a constant it finds unrecorded forces.
        val roots = ownRoots()
        if (roots.isEmpty() && ownJars.isEmpty()) return null
        val digests = mutableMapOf<String, String>()
        val refusal = walkOwnClasses(roots) { root, entry ->
            val bytes = runCatching { entry.readBytes() }.getOrNull()
                ?: return@walkOwnClasses "${entry.absolutePath} could not be read"
            Recordability.constantDigest(bytes)?.let { digests[classNameOf(root, entry)] = it }
            null
        }
        if (refusal != null) return null

        for (jar in ownJars) {
            val ok = runCatching {
                ZipFile(jar).use { zip ->
                    for (entry in zip.entries()) {
                        if (entry.isDirectory || !entry.name.endsWith(".class")) continue
                        if (entry.size > MAX_RESOURCE_BYTES) return null
                        val bytes = zip.getInputStream(entry).use { it.readBytes() }
                        Recordability.constantDigest(bytes)?.let {
                            digests[entry.name.removeSuffix(".class").replace('/', '.')] = it
                        }
                    }
                }
                true
            }.getOrDefault(false)
            if (!ok) return null
        }
        return digests
    }

    /**
     * `className -> class digest` for every own class. Sees constants and `inline` bodies copied
     * into a consumer whose source `git diff` shows unchanged. Complete or refused, never partial:
     * a class missing from a partial map would read as a matching digest.
     */
    internal fun classDigests(
        /** What each class digests to; the annotation table walks the same classes. */
        digestOf: (ByteArray) -> Recordability.Digest = Recordability::classDigest,
    ): DigestScan {
        val roots = ownRoots()
        if (roots.isEmpty() && ownJars.isEmpty()) {
            return DigestScan.Refused(
                "there is nothing to walk: no own output directory and no own jar on the classpath",
                DigestScan.Kind.NOTHING_TO_SCAN,
                DigestScan.Counts(),
            )
        }
        val unaccounted = directories.filter { it.isDirectory && it !in roots }.firstOrNull(::holdsClasses)
        if (unaccounted != null) {
            return DigestScan.Refused(
                "${unaccounted.absolutePath} is on the classpath, holds classes, and is nobody's module output",
                DigestScan.Kind.UNACCOUNTED_OUTPUT,
                DigestScan.Counts(),
            )
        }

        val digests = mutableMapOf<String, String>()
        var considered = 0
        var unreadable = 0
        val stopped = walkOwnClasses(roots) { root, entry ->
            considered++
            val bytes = runCatching { entry.readBytes() }.getOrNull()
            if (bytes == null) {
                unreadable++
                return@walkOwnClasses "${entry.absolutePath} could not be read"
            }
            val digest = digestOf(bytes)
            val value = digest.value
            if (value == null) {
                unreadable++
                return@walkOwnClasses "${entry.absolutePath} ${digest.reason}"
            }
            digests[classNameOf(root, entry)] = value
            null
        }
        if (stopped != null) {
            return DigestScan.Refused(
                stopped.reason,
                when {
                    stopped.budgetSpent -> DigestScan.Kind.BUDGET_SPENT
                    else -> DigestScan.Kind.UNREADABLE
                },
                DigestScan.Counts(considered, digests.size, unreadable),
            )
        }

        for (jar in ownJars) {
            var refusal: DigestScan.Refused? = null
            val opened = runCatching {
                ZipFile(jar).use { zip ->
                    for (entry in zip.entries()) {
                        if (entry.isDirectory || !entry.name.endsWith(".class")) continue
                        considered++
                        if (entry.size > MAX_RESOURCE_BYTES) {
                            refusal = DigestScan.Refused(
                                "${jar.absolutePath}!${entry.name} is larger than the " +
                                    "$MAX_RESOURCE_BYTES-byte read cap",
                                DigestScan.Kind.UNREADABLE,
                                DigestScan.Counts(considered, digests.size, unreadable + 1),
                            )
                            return@use
                        }
                        val bytes = zip.getInputStream(entry).use { it.readBytes() }
                        val digest = digestOf(bytes)
                        val value = digest.value
                        if (value == null) {
                            unreadable++
                            refusal = DigestScan.Refused(
                                "${jar.absolutePath}!${entry.name} ${digest.reason}",
                                DigestScan.Kind.UNREADABLE,
                                DigestScan.Counts(considered, digests.size, unreadable),
                            )
                            return@use
                        }
                        // A multi-release entry keys as `META-INF.versions.9...`: it always reads
                        // as changed and selects nothing, but inflates a count of changed classes.
                        digests[entry.name.removeSuffix(".class").replace('/', '.')] = value
                    }
                }
            }.isSuccess
            refusal?.let { return it }
            if (!opened) {
                return DigestScan.Refused(
                    "${jar.absolutePath} could not be opened",
                    DigestScan.Kind.UNREADABLE,
                    DigestScan.Counts(considered, digests.size, unreadable + 1),
                )
            }
        }
        return DigestScan.Found(digests, DigestScan.Counts(considered, digests.size, unreadable))
    }

    // Anything unsettled (unlistable, over budget) answers true, which forces.
    private fun holdsClasses(directory: File): Boolean {
        var visited = 0
        val stack = ArrayDeque(listOf(directory))
        while (stack.isNotEmpty()) {
            val entries = stack.removeLast().listFiles() ?: return true
            visited += entries.size
            if (visited > MAX_FILES) return true
            for (entry in entries) {
                if (entry.isDirectory) stack.addLast(entry)
                else if (entry.name.endsWith(".class")) return true
            }
        }
        return false
    }

    private fun isOwnOutput(directory: File): Boolean =
        moduleBuildDirs.any { directory.absolutePath.startsWith(it + File.separator) }

    private fun matches(inlined: Set<String>, prefixes: Collection<String>): Boolean =
        // Prefix, so `pkg.Inlined` matches `pkg.InlinedKt`; over-selecting is the safe direction.
        inlined.isNotEmpty() && inlined.any { name -> prefixes.any { name.startsWith(it) } }

    /** Whether the SMAP signal exists in this build at all, from bytes already being read. */
    private class SmapEvidence {
        private var anyKotlin = false
        private var anySmap = false
        private var scanned = 0
        private var kotlin = 0

        /** False when this class could not be understood, which ends the scan. */
        fun accept(scan: InlinedSources.Scan): Boolean {
            if (scan.unknown) return false
            scanned++
            if (scan.kotlin) kotlin++
            anyKotlin = anyKotlin || scan.kotlin
            anySmap = anySmap || scan.carriesSmap
            return true
        }

        /** A Kotlin output that emitted no SMAP anywhere cannot answer this question. */
        fun usable(): Boolean = !anyKotlin || anySmap

        // The Kotlin share tells a Java project with a little Kotlin from a Kotlin build compiled
        // without the signal; the two want opposite remedies.
        fun census(): String =
            "$scanned classes scanned, $kotlin of them Kotlin, and none anywhere carries a " +
                "SourceDebugExtension"
    }

    // Memoised on path, size and mtime: every selecting task rescans the same classes in a
    // `doFirst` with the test JVM waiting.
    private fun scanOf(classFile: File): InlinedSources.Scan? {
        val key = classFile.absolutePath + "|" + classFile.length() + "|" + classFile.lastModified()
        scans[key]?.let { return it }
        val bytes = runCatching { classFile.readBytes() }.getOrNull() ?: return null
        return InlinedSources.scan(bytes).also { scans[key] = it }
    }

    private val scans = mutableMapOf<String, InlinedSources.Scan>()

    /** The class file's major version, so a refusal shows a toolchain mismatch; -1 if too short. */
    private fun classFileVersion(bytes: ByteArray): Int =
        if (bytes.size >= 8) ((bytes[6].toInt() and 0xff) shl 8) or (bytes[7].toInt() and 0xff) else -1

    /** [InlineScan.Refused] when the jar could not be read, which is an unfinished scan. */
    private fun scanJar(
        jar: File,
        prefixes: Collection<String>,
        found: MutableSet<String>,
        evidence: SmapEvidence,
    ): InlineScan = runCatching {
        ZipFile(jar).use { zip ->
            for (entry in zip.entries()) {
                if (entry.isDirectory || !entry.name.endsWith(".class")) continue
                if (entry.size > MAX_RESOURCE_BYTES) {
                    return InlineScan.Refused(
                        "${jar.name}!${entry.name} is over the read cap",
                        InlineScan.Kind.INCOMPLETE,
                    )
                }
                val bytes = zip.getInputStream(entry).use { it.readBytes() }
                val scan = InlinedSources.scan(bytes)
                if (!evidence.accept(scan)) {
                    return InlineScan.Refused(
                        "${jar.name}!${entry.name} (class file v${classFileVersion(bytes)})" +
                            " was not understood: ${InlinedSources.diagnose(bytes)}",
                        InlineScan.Kind.INCOMPLETE,
                    )
                }
                if (matches(scan.inlined, prefixes)) {
                    found += entry.name.removeSuffix(".class").replace('/', '.')
                }
            }
        }
        InlineScan.Found(found)
    }.getOrElse { problem ->
        InlineScan.Refused(
            "${jar.absolutePath} could not be scanned: ${problem::class.java.name}" +
                (problem.message?.let { ": $it" } ?: " with no message"),
            InlineScan.Kind.INCOMPLETE,
        )
    }

    private fun classNameOf(root: File, classFile: File): String =
        classFile.absolutePath
            .removePrefix(root.absolutePath)
            .trimStart(File.separatorChar)
            .removeSuffix(".class")
            .replace(File.separatorChar, '.')
            .replace('/', '.')

    private companion object {
        /** Read as bytes, so a resource in any encoding is searched for an ASCII class name. */
        const val MAX_RESOURCE_BYTES = 4L * 1024 * 1024

        const val MAX_FILES = 200_000

        /** Dependency-jar bytes read before forcing; class files are skipped, not counted. */
        const val MAX_SCAN_BYTES = 128L * 1024 * 1024
    }
}
