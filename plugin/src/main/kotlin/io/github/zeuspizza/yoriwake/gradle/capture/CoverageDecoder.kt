package io.github.zeuspizza.yoriwake.gradle.capture

import io.github.zeuspizza.yoriwake.agent.contract.AgentContract.CLASS_SCOPED_RECORD_PREFIX
import io.github.zeuspizza.yoriwake.agent.contract.AgentContract.COMPLETE_SUFFIX
import io.github.zeuspizza.yoriwake.agent.contract.AgentContract.COVERAGE_FILE
import io.github.zeuspizza.yoriwake.agent.contract.AgentContract.EFFECTIVE_SCOPE_FILE
import io.github.zeuspizza.yoriwake.agent.contract.AgentContract.EXEC_FILE_FORMAT
import io.github.zeuspizza.yoriwake.agent.contract.AgentContract.FIRST_TOUCH_ANY
import io.github.zeuspizza.yoriwake.agent.contract.AgentContract.FIRST_TOUCH_FILE
import io.github.zeuspizza.yoriwake.agent.contract.AgentContract.INDEX_FILE
import io.github.zeuspizza.yoriwake.agent.contract.AgentContract.JVM_MODE_FILE
import io.github.zeuspizza.yoriwake.agent.contract.AgentContract.LOADED_FILE
import io.github.zeuspizza.yoriwake.agent.contract.AgentContract.LOADED_PROVENANCE_FILE
import io.github.zeuspizza.yoriwake.agent.contract.AgentContract.LOADED_SCOPE_FILE
import io.github.zeuspizza.yoriwake.agent.contract.AgentContract.LOADED_WORKER_PREFIX
import io.github.zeuspizza.yoriwake.agent.contract.AgentContract.LOADED_WORKER_SUFFIX
import io.github.zeuspizza.yoriwake.agent.contract.AgentContract.MAP_SCHEMA_VERSION
import io.github.zeuspizza.yoriwake.agent.contract.AgentContract.MAP_SCHEMA_VERSION_FILE
import io.github.zeuspizza.yoriwake.agent.contract.AgentContract.MODE_ISOLATED
import io.github.zeuspizza.yoriwake.agent.contract.AgentContract.MODE_SHARED
import io.github.zeuspizza.yoriwake.agent.contract.AgentContract.NAMED_TOUCH_FILE
import io.github.zeuspizza.yoriwake.agent.contract.AgentContract.OUTCOME_NOT_A_TEST
import io.github.zeuspizza.yoriwake.agent.contract.AgentContract.OUTCOME_SUCCESSFUL
import io.github.zeuspizza.yoriwake.agent.contract.AgentContract.OUTCOME_UNKNOWN
import io.github.zeuspizza.yoriwake.agent.contract.AgentContract.PLAN_COMPLETE_FILE
import io.github.zeuspizza.yoriwake.agent.contract.AgentContract.POSITIONS_FILE
import io.github.zeuspizza.yoriwake.agent.contract.AgentContract.RAW_DIR
import io.github.zeuspizza.yoriwake.agent.contract.AgentContract.RAW_SCHEMA_VERSION
import io.github.zeuspizza.yoriwake.agent.contract.AgentContract.RAW_SCHEMA_VERSION_FILE
import io.github.zeuspizza.yoriwake.agent.contract.AgentContract.SCOPE_FILE
import io.github.zeuspizza.yoriwake.agent.contract.AgentContract.TOUCHES_COMPLETE
import io.github.zeuspizza.yoriwake.agent.contract.AgentContract.TOUCHES_FILE
import io.github.zeuspizza.yoriwake.agent.contract.AgentContract.TOUCH_ALL
import io.github.zeuspizza.yoriwake.agent.contract.AgentContract.TOUCH_DEFINED
import io.github.zeuspizza.yoriwake.agent.contract.AgentContract.TOUCH_JAR
import io.github.zeuspizza.yoriwake.agent.contract.AgentContract.TOUCH_LOADED
import io.github.zeuspizza.yoriwake.agent.contract.AgentContract.TOUCH_LOOKUP
import io.github.zeuspizza.yoriwake.agent.contract.AgentContract.TOUCH_PLAN_STARTED
import io.github.zeuspizza.yoriwake.agent.contract.AgentContract.TOUCH_READ
import io.github.zeuspizza.yoriwake.agent.contract.AgentContract.UNATTRIBUTED_RECORD_ID
import io.github.zeuspizza.yoriwake.agent.contract.AgentContract.WORKER_DIR_PREFIX
import io.github.zeuspizza.yoriwake.agent.contract.Tsv
import io.github.zeuspizza.yoriwake.gradle.change.WorkingTree
import org.jacoco.core.data.ExecutionData
import org.jacoco.core.data.ExecutionDataReader
import org.jacoco.core.data.ExecutionDataStore
import org.jacoco.core.data.SessionInfoStore
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.zip.ZipFile

/** A map that could not be written. Caught by the caller; never allowed to fail the host build. */
internal class MapNotWritten(target: File) : Exception("[yoriwake] could not write $target")

/** Turns raw agent records into the text the selector reads, one line per record:
 * `outcome \t durationNanos \t class,class,class \t testId` (`testId` last: it may hold any text).
 * Decoded here, daemon side, because the agent must carry no unshaded `org.jacoco.core`.
 */
internal object CoverageDecoder {

    /** What a decode did to the map: what this run captured, and what the map now holds. */
    data class Outcome(
        val captured: Int,
        val total: Int,
        /** This run discarded an unvouched loaded-class union, a loss otherwise invisible. */
        val unionDiscarded: Boolean = false,
        /** Records rewritten to UNKNOWN for want of an in-scope class; they run on every build. */
        val unattributable: Int = 0,
        /** Classless test records in the merged map, which may be a restored cache. */
        val mapUnattributable: Int = 0,
        val mapTests: Int = 0,
        /** Worker directories read; durations are summed across them, so the audit needs this. */
        val workers: Int = 0,
    )

    /** `className<TAB>digest` for classes declaring a compile-time constant. Absent forces. */
    const val CONSTANTS_FILE = "constants"

    /**
     * `className<TAB>digest` for every class in this build's output: catches constants and Kotlin
     * `inline` bodies copied into a consumer whose source did not change. Only ever widens.
     *
     * No MAP_SCHEMA_VERSION bump: a missing table reads as unknown and an unbuildable one is
     * deleted, so neither passes for agreement. Once a digest match may retire a forcing, the bump
     * is owed.
     */
    const val CLASS_DIGESTS_FILE = "class-digests"

    /**
     * `className<TAB>annotation digest` for every class in this build's output, so a change to an
     * annotation forces even where no test executes the class. Plugin-private, like the tables
     * above: no MAP_SCHEMA_VERSION bump, because a missing or torn table forces rather than
     * passing for agreement.
     */
    const val ANNOTATION_DIGESTS_FILE = "annotation-digests"

    /** The commit the map was last captured at. Absent means "unknown", never "current". */
    const val CAPTURE_COMMIT_FILE = "capture-commit"

    /**
     * Record directories of workers whose test JVM did not finish, sorted. Flushed or not, such a
     * worker leaves older records standing, so the map is not current and may not be dated.
     */
    fun unfinishedWorkers(mapDir: File): List<String> =
        recordsDir(mapDir)
            .listFiles { f: File -> f.isDirectory && f.name.startsWith(WORKER_DIR_PREFIX) }
            .orEmpty()
            .filterNot { File(it, PLAN_COMPLETE_FILE).isFile }
            .map(File::getName)
            .sorted()

    /**
     * How the map's JVMs that ran a test were recorded: [MODE_ISOLATED] or [MODE_SHARED] when they
     * all agree, [MIXED_MODES] when they do not. A JVM with no row, or any other value, is shared.
     */
    fun captureMode(mapDir: File): String {
        val rows = { name: String ->
            File(mapDir, name).takeIf(File::isFile)?.readLines()?.filter(String::isNotBlank).orEmpty().map(Tsv::split)
        }
        val isolated = rows(JVM_MODE_FILE).filter { it.size == 2 && it[1] == MODE_ISOLATED }.mapTo(HashSet()) { it[0] }
        val modes = rows(POSITIONS_FILE).mapTo(HashSet()) { if (it.first() in isolated) MODE_ISOLATED else MODE_SHARED }
        return modes.singleOrNull() ?: if (modes.isEmpty()) MODE_SHARED else MIXED_MODES
    }

    /** [captureMode] of a map some of whose JVMs were isolated and some not. */
    const val MIXED_MODES = "mixed"

    /** Where the agent is told to write its per-worker loaded-class lists. */
    fun loadedDir(mapDir: File): File = File(mapDir, "loaded")

    /** Where the agent is told to write raw records for a given map. */
    fun recordsDir(mapDir: File): File = File(mapDir, RAW_DIR)

    /**
     * Decodes every worker's records and merges them into the coverage file, superseding only the
     * ids this run re-observed: a selected run would otherwise discard everything it skipped. A
     * run that dates the map ([datesTheMap]) keeps no test record it did not re-observe: the stamp
     * would make it read as current. Returns null when nothing was captured, which differs from
     * tests that covered nothing.
     */
    fun decode(
        mapDir: File,
        includePackages: List<String>,
        selecting: Boolean = false,
        wholeTask: Boolean = false,
        loadedScope: List<String> = emptyList(),
        captureCommit: String? = null,
        datesTheMap: Boolean = false,
        effectiveScope: String? = null,
        /** See [CONSTANTS_FILE]. Null when the classes could not be read; the file is then removed. */
        constants: Map<String, String>? = null,
        /** See [CLASS_DIGESTS_FILE]. Null when the walk did not finish; the file is then removed. */
        classDigests: Map<String, String>? = null,
        /** See [ANNOTATION_DIGESTS_FILE]. Null when the walk did not finish; the file is then removed. */
        annotationDigests: Map<String, String>? = null,
        /** See [WorkingTree]. Asked once records merge; a null answer removes the snapshot. */
        worktreeSnapshot: (() -> String?)? = null,
        /** Whether this capture forked a JVM per test class; see [JVM_MODE_FILE]. */
        isolated: Boolean = false,
    ): Outcome? {
        val workers = recordsDir(mapDir)
            .listFiles { f: File -> f.isDirectory && f.name.startsWith(WORKER_DIR_PREFIX) }
            ?.sortedBy(File::getName)
            .orEmpty()
        if (workers.isEmpty()) return null

        refuseForeignWorkers(workers)

        // One id per worker of this capture, so positions from two captures never mix in a merge.
        val run = UUID.randomUUID().toString().substring(0, 8)
        val decoded = workers.map { worker -> decodeWorker(worker, includePackages, "$run/${worker.name}", isolated) }
        val captured = decoded.flatMap(WorkerRecords::lines)
        if (captured.isEmpty()) return null

        // Keyed by id but not deduplicated by it: a class-scoped id appears once per coverage
        // window, and each window carries different classes.
        val recaptured = captured.mapTo(HashSet(), ::testIdOf)
        // A window no test owns whose blob could not be read has nothing to replace its older
        // records with, so they all stay: they only ever force or select.
        val unread = decoded.flatMapTo(HashSet(), WorkerRecords::unreadUnowned)
        // A selecting run does not supersede startup coverage: all its windows share one id, and
        // coverage no test owns is what forces a full run when touched.
        val superseded: (String) -> Boolean = { line ->
            val id = testIdOf(line)
            id in recaptured && !(selecting && id == UNATTRIBUTED_RECORD_ID) && id !in unread
        }
        // A test the dating run did not report (deleted, filtered out, under a class whose setup
        // failed) drops out of the map, and a test not in the map runs. Startup coverage a
        // selecting run did not replace stays: it only ever forces.
        val keptByDatingRun: (String) -> Boolean = { line ->
            val id = testIdOf(line)
            (selecting && id == UNATTRIBUTED_RECORD_ID) || id in unread
        }
        val retained = existingLines(mapDir).filterNot(superseded).filter { !datesTheMap || keptByDatingRun(it) }
        val merged = (retained + captured).distinct()
        val (positions, firstTouches, namedTouches, modes) = mergeOrder(mapDir, recaptured, decoded, datesTheMap)

        mapDir.mkdirs()
        // Version marker off first, back on last: each write is atomic but the set is not, and
        // MapReader refuses an unversioned map, so an interrupted decode forces a full run.
        File(mapDir, MAP_SCHEMA_VERSION_FILE).delete()
        writeAtomically(File(mapDir, COVERAGE_FILE), merged.joinToString("\n", postfix = "\n"))
        writeAtomically(File(mapDir, POSITIONS_FILE), positions.joinToString("") { "$it\n" })
        writeAtomically(File(mapDir, FIRST_TOUCH_FILE), firstTouches.joinToString("") { "$it\n" })
        writeAtomically(File(mapDir, NAMED_TOUCH_FILE), namedTouches.joinToString("") { "$it\n" })
        writeAtomically(File(mapDir, JVM_MODE_FILE), modes.joinToString("") { "$it\n" })
        writeScope(mapDir, includePackages)
        writeEffectiveScope(
            mapDir,
            effectiveScope,
            carriesOlderRecords = retained.isNotEmpty(),
            fullCapture = datesTheMap,
        )
        val unionDiscarded = writeLoaded(mapDir, selecting, wholeTask, workers.size, loadedScope)
        writeCaptureCommit(mapDir, captureCommit, datesTheMap)
        writeConstants(mapDir, constants, datesTheMap)
        writeDigestTable(File(mapDir, CLASS_DIGESTS_FILE), classDigests, datesTheMap)
        writeDigestTable(File(mapDir, ANNOTATION_DIGESTS_FILE), annotationDigests, datesTheMap)
        // Asked only by a dating capture: no other merges records.
        worktreeSnapshot?.let { observe ->
            val snapshot = File(mapDir, WorkingTree.SNAPSHOT_FILE)
            observe()?.let { writeAtomically(snapshot, it) } ?: snapshot.delete()
        }
        writeAtomically(File(mapDir, MAP_SCHEMA_VERSION_FILE), "$MAP_SCHEMA_VERSION\n")
        val unattributable = captured.count { line ->
            val parts = line.split('	')
            parts.size >= 4 && parts[0] == OUTCOME_UNKNOWN && parts[2].isBlank()
        }
        val mapTestRecords = merged.filter { line ->
            val id = testIdOf(line)
            !id.startsWith(CLASS_SCOPED_RECORD_PREFIX) && id != UNATTRIBUTED_RECORD_ID
        }
        return Outcome(
            captured.size,
            merged.size,
            unattributable = unattributable,
            mapUnattributable = mapTestRecords.count { line ->
                val parts = line.split('	')
                parts.size >= 4 && parts[2].isBlank()
            },
            mapTests = mapTestRecords.size,
            workers = workers.size,
        )
    }

    /**
     * What a capture that does not date the map keeps of its records: the outcome of every test it
     * saw end without succeeding (failed, aborted or skipped), so a failing test runs until a capture
     * sees it pass. Their coverage and every other record stay as they were, so nothing in the map
     * describes a newer commit than its stamp; an outcome only ever runs a test. Such a test the map
     * does not hold is added with no coverage, which runs it as surely. Returns how many were marked.
     */
    fun carryFailures(mapDir: File): Int {
        val failed = LinkedHashMap<String, String>()
        recordsDir(mapDir).listFiles { f: File -> f.isDirectory && f.name.startsWith(WORKER_DIR_PREFIX) }
            .orEmpty().sortedBy(File::getName).forEach { worker ->
                File(worker, INDEX_FILE).takeIf(File::isFile)?.readLines().orEmpty().forEach { line ->
                    // sequence, durationNanos, byteCount, outcome, testId
                    val parts = Tsv.split(line)
                    if (parts.size != 5) return@forEach
                    val (outcome, testId) = parts[3] to parts[4]
                    val attributable = !testId.startsWith(CLASS_SCOPED_RECORD_PREFIX) && testId != UNATTRIBUTED_RECORD_ID
                    if (attributable && outcome != OUTCOME_SUCCESSFUL && outcome != OUTCOME_NOT_A_TEST) {
                        failed.putIfAbsent(testId, outcome)
                    }
                }
            }
        if (failed.isEmpty()) return 0
        val existing = existingLines(mapDir)
        // No map of this version: every test runs anyway.
        if (existing.isEmpty()) return 0
        // The map holds ids escaped, the records' index as Tsv.split returned them.
        val idOf = { line: String -> Tsv.unescape(testIdOf(line)) }
        val held = existing.mapTo(HashSet(), idOf)
        val marked = existing.map { line ->
            failed[idOf(line)]?.let { outcome -> outcome + line.substring(line.indexOf('\t')) } ?: line
        } + failed.filterKeys { it !in held }.map { (id, outcome) -> Tsv.join(outcome, "0", "", id) }
        writeAtomically(File(mapDir, COVERAGE_FILE), marked.joinToString("\n", postfix = "\n"))
        return failed.size
    }

    /**
     * Only a full capture may touch the constants (the [writeCaptureCommit] gate): a digest that
     * advances past the retained coverage would license a skip. Older values mismatch and force.
     */
    private fun writeConstants(mapDir: File, constants: Map<String, String>?, fullCapture: Boolean) {
        val file = File(mapDir, CONSTANTS_FILE)
        if (!fullCapture) {
            return
        }
        if (constants == null) {
            file.delete()
            return
        }
        writeAtomically(
            file,
            constants.entries.sortedBy { it.key }
                .joinToString(separator = "\n", postfix = "\n") { "${it.key}\t${it.value}" },
        )
    }

    /**
     * Same gate as [writeConstants]. An unfinished walk deletes the file rather than leaving the
     * old one, which keeps each table's no-schema-bump argument true.
     */
    private fun writeDigestTable(file: File, digests: Map<String, String>?, fullCapture: Boolean) {
        if (!fullCapture) {
            return
        }
        if (digests == null) {
            file.delete()
            return
        }
        writeAtomically(
            file,
            digests.entries.sortedBy { it.key }
                .joinToString(separator = "\n", postfix = "\n") { "${it.key}\t${it.value}" },
        )
    }

    /**
     * Reads [CLASS_DIGESTS_FILE], or null when there is no table this can vouch for. Stricter than
     * [readConstants] because a missing entry here selects fewer tests: a file without its final
     * newline (which [writeAtomically] always writes) or with a malformed line is refused whole.
     */
    fun readClassDigests(mapDir: File): Map<String, String>? =
        readDigestTable(File(mapDir, CLASS_DIGESTS_FILE))

    /** Reads [ANNOTATION_DIGESTS_FILE] as strictly as [readClassDigests]; null forces. */
    fun readAnnotationDigests(mapDir: File): Map<String, String>? =
        readDigestTable(File(mapDir, ANNOTATION_DIGESTS_FILE))

    private fun readDigestTable(file: File): Map<String, String>? = runCatching {
        if (!file.isFile) return null
        val text = file.readText()
        if (text.isEmpty()) return emptyMap()
        if (!text.endsWith("\n")) return null
        // Only line breaks is an empty table (a module with no classes), not a torn one.
        val body = text.trimEnd('\n')
        if (body.isEmpty()) return emptyMap()
        val digests = mutableMapOf<String, String>()
        for (line in body.split('\n')) {
            val parts = line.split('\t', limit = 2)
            if (parts.size != 2 || parts[0].isBlank() || parts[1].isBlank()) return null
            digests[parts[0]] = parts[1]
        }
        digests
    }.getOrNull()

    /** Reads [CONSTANTS_FILE]. Empty when absent or unreadable, which forces as it always did. */
    fun readConstants(mapDir: File): Map<String, String> = runCatching {
        val file = File(mapDir, CONSTANTS_FILE)
        if (!file.isFile) return emptyMap()
        file.readLines()
            .mapNotNull { line ->
                val parts = line.split('	', limit = 2)
                if (parts.size == 2 && parts[0].isNotBlank()) parts[0] to parts[1] else null
            }
            .toMap()
    }.getOrElse { emptyMap() }

    /** Unscoped writes no file: empty must keep meaning "we do not know". */
    private fun writeScope(mapDir: File, includePackages: List<String>) {
        val file = File(mapDir, SCOPE_FILE)
        if (includePackages.isEmpty()) {
            file.delete()
        } else {
            writeAtomically(file, includePackages.joinToString("\n", postfix = "\n"))
        }
    }

    /**
     * Records the scopes every record in the map was captured under. A full capture replaces the
     * file, any other run appends its scope, and a run that could not read its scope deletes it.
     *
     * A full capture keeps no test record it did not re-observe (see [decode]), so its scope speaks
     * for the map's tests.
     */
    private fun writeEffectiveScope(
        mapDir: File,
        serialized: String?,
        carriesOlderRecords: Boolean,
        fullCapture: Boolean,
    ) {
        val file = File(mapDir, EFFECTIVE_SCOPE_FILE)
        val previous = if (file.isFile) runCatching { file.readText() }.getOrNull() else null
        if (serialized == null) {
            file.delete()
            return
        }
        val content = when {
            fullCapture || !carriesOlderRecords -> serialized
            previous == null -> null
            previous.contains(serialized.trim()) -> previous
            else -> previous.trimEnd() + "\n" + serialized
        }
        if (content == null) {
            // Older records, and no record of the scope they were captured under.
            file.delete()
        } else {
            writeAtomically(file, content)
        }
    }

    /**
     * Replaces the loaded-class union from a full run. A selecting run contributes nothing: a
     * narrowed set looks exactly like a complete one and would make the selector skip more.
     */
    private fun writeLoaded(
        mapDir: File,
        selecting: Boolean,
        wholeTask: Boolean,
        workers: Int,
        loadedScope: List<String>,
    ): Boolean {
        val directory = loadedDir(mapDir)
        val perWorker = directory
            .listFiles { f: File -> f.isFile && f.name.startsWith(LOADED_WORKER_PREFIX) && f.name.endsWith(LOADED_WORKER_SUFFIX) }
            .orEmpty()
        // Completion markers, not lists: a --fail-fast JVM writes a list but does not finish.
        val completed = directory
            .listFiles { f: File -> f.isFile && f.name.endsWith(COMPLETE_SUFFIX) }
            .orEmpty()

        val trustworthy = wholeTask &&
            !selecting &&
            loadedScope.isNotEmpty() &&
            perWorker.size == workers &&
            completed.size == workers

        if (!trustworthy) {
            if (selecting) {
                // The union still describes the full run it came from.
                return false
            }
            return discardUnion(mapDir)
        }

        // Filtered here, not at capture, so the complete set speaks for the stored scope.
        val names = perWorker
            .flatMap { it.readLines() }
            .map(String::trim)
            .filter { name -> name.isNotEmpty() && loadedScope.any { inPackage(name, it) } }
            .toSortedSet()
        if (names.isEmpty()) {
            return discardUnion(mapDir)
        }

        // Provenance last, and removed first: a reader that finds no provenance refuses the union,
        // so an interrupted write leaves it untrusted rather than half-described.
        File(mapDir, LOADED_PROVENANCE_FILE).delete()
        writeAtomically(File(mapDir, LOADED_FILE), names.joinToString("\n", postfix = "\n"))
        writeAtomically(File(mapDir, LOADED_SCOPE_FILE), loadedScope.joinToString("\n", postfix = "\n"))
        writeAtomically(File(mapDir, LOADED_PROVENANCE_FILE), "full\n")
        return false
    }

    private fun inPackage(className: String, prefix: String) =
        className == prefix || className.startsWith("$prefix.")

    /**
     * Drops the union and reports whether there was one, so the caller can say so: a full run
     * without `-Pyoriwake.internal.loaded` otherwise silently ends narrowing of unexercised code.
     */
    private fun discardUnion(mapDir: File): Boolean {
        val had = File(mapDir, LOADED_FILE).isFile
        File(mapDir, LOADED_PROVENANCE_FILE).delete()
        File(mapDir, LOADED_FILE).delete()
        File(mapDir, LOADED_SCOPE_FILE).delete()
        return had
    }

    /** The map as it stands, or nothing when another schema version wrote it. */
    private fun existingLines(mapDir: File): List<String> {
        val version = File(mapDir, MAP_SCHEMA_VERSION_FILE).takeIf(File::isFile)?.readText()?.trim()
        if (version != "$MAP_SCHEMA_VERSION") return emptyList()
        return File(mapDir, COVERAGE_FILE).takeIf(File::isFile)
            ?.readLines()
            ?.filter(String::isNotBlank)
            .orEmpty()
    }

    /** Refuses the whole capture, not one worker, when records lack this version's stamp. */
    private fun refuseForeignWorkers(workers: List<File>) {
        workers.forEach { worker ->
            val index = File(worker, INDEX_FILE)
            // An agent killed between creating the index and stamping it leaves no records.
            if (!index.isFile || index.length() == 0L) return@forEach
            val stamped = File(worker, RAW_SCHEMA_VERSION_FILE).takeIf(File::isFile)?.readText()?.trim()
            if (stamped != "$RAW_SCHEMA_VERSION") {
                throw IOException(
                    "$worker holds records written by schema version ${stamped ?: "unknown"}, but " +
                        "this decoder reads version $RAW_SCHEMA_VERSION. Refusing to merge them: the " +
                        "columns may not mean the same thing. Delete ${worker.parentFile} and run " +
                        "the suite again."
                )
            }
        }
    }

    /** A record is keyed by its test id, the last tab-separated field. */
    private fun testIdOf(line: String): String = line.substringAfterLast('\t')

    /** One worker's decoded records, where each test ran, and when each class first arrived. */
    private class WorkerRecords(
        val lines: List<String>,
        /** `jvm \t sequence \t testId` rows. */
        val positions: List<String>,
        /** `jvm \t sequence \t class` rows, one per class. */
        val firstTouches: List<String>,
        /** As [firstTouches], counting only what can reach a test class from outside its own tests. */
        val namedTouches: List<String>,
        /** The `jvm \t mode` row. */
        val mode: String,
        /** The ids of windows no test owns whose blob could not be read. */
        val unreadUnowned: Set<String> = emptySet(),
    )

    /**
     * Engines known to instantiate the test classes they run and to build their tests from those
     * classes alone. Under any other engine, discovery may have turned one test class's metadata
     * into another class's tests, so no test class of that JVM is dated by named touches alone.
     */
    private val ENGINES_THAT_OWN_THEIR_CLASSES = setOf(
        "junit-jupiter", "junit-vintage", "junit-platform-suite", "junit4", "testng",
        "spock", "kotest", "archunit", "jqwik",
    )

    private fun engineOf(testId: String): String? =
        if (testId.startsWith("[engine:")) testId.substring("[engine:".length).substringBefore(']') else null

    /** The binary names of the test classes that own a record: none for startup coverage. */
    private fun ownersOf(testId: String): List<String> {
        if (testId == UNATTRIBUTED_RECORD_ID) return emptyList()
        return Regex("""\[class:([^\]]+)]""").findAll(testId).map { it.groupValues[1] }.toList()
    }

    private fun ownedBy(className: String, owner: String) =
        className == owner || className == owner + "Kt" || className.startsWith("$owner$")

    private fun decodeWorker(worker: File, includePackages: List<String>, jvm: String, isolated: Boolean): WorkerRecords {
        val index = File(worker, INDEX_FILE)
        if (!index.isFile) return WorkerRecords(emptyList(), emptyList(), emptyList(), emptyList(), Tsv.join(jvm, MODE_SHARED))
        val inScope = { name: String -> includePackages.isEmpty() || includePackages.any { name.startsWith(it) } }
        val first = HashMap<String, Int>()
        val touch = { name: String, sequence: Int -> first.merge(name, sequence, ::minOf); Unit }
        val named = HashMap<String, Int>()
        val touchNamed = { name: String, sequence: Int -> named.merge(name, sequence, ::minOf); Unit }
        val positions = mutableListOf<String>()
        var unknownEngine = false
        // The outermost class of each test this JVM ran; null for a test id that names none.
        val testClasses = HashSet<String?>()
        val unreadUnowned = HashSet<String>()

        val lines = index.readLines().filter(String::isNotBlank).mapNotNull { line ->
            // sequence, durationNanos, byteCount, outcome, testId
            val parts = Tsv.split(line)
            if (parts.size != 5) return@mapNotNull null
            val sequence = parts[0].toIntOrNull() ?: return@mapNotNull null

            val exec = File(worker, EXEC_FILE_FORMAT.format(sequence))
            val testId = parts[4]
            val attributable = !testId.startsWith(CLASS_SCOPED_RECORD_PREFIX) &&
                testId != UNATTRIBUTED_RECORD_ID
            // An unreadable blob makes a test UNKNOWN, which always runs, rather than leaving its
            // older record standing. A window no test owns has no outcome to spend, so it yields
            // no record and the older ones survive; see decode.
            val classes = coveredClasses(exec) ?: if (attributable) emptySet() else {
                unreadUnowned += testId
                return@mapNotNull null
            }
            val inScopeClasses = classes.filter(inScope)
            inScopeClasses.forEach { touch(it, sequence) }
            // A class executed in some other class's window was reached from outside its own tests.
            val owners = ownersOf(testId)
            inScopeClasses.filter { name -> owners.none { ownedBy(name, it) } }.forEach { touchNamed(it, sequence) }
            // Empty is not "covers nothing" (e.g. Robolectric's sandboxed classloader): record the
            // test UNKNOWN so it always runs, instead of SUCCESSFUL and never selected again.
            if (attributable) {
                positions += Tsv.join(jvm, sequence.toString(), testId)
                testClasses += owners.firstOrNull()
                if (engineOf(testId) !in ENGINES_THAT_OWN_THEIR_CLASSES) unknownEngine = true
            }
            val outcome = if (inScopeClasses.isEmpty() && attributable) OUTCOME_UNKNOWN else parts[3]
            Tsv.join(outcome, parts[1], inScopeClasses.sorted().joinToString(","), testId)
        }
        readTouches(worker, inScope, touch, touchNamed)
        if (unknownEngine) touchNamed(FIRST_TOUCH_ANY, 0)
        val rows = { touches: Map<String, Int> ->
            touches.entries.sortedBy { it.key }.map { (name, sequence) -> Tsv.join(jvm, sequence.toString(), name) }
        }
        // Checked, not assumed from the flag: a host that forks differently still gets the truth.
        val mode = if (isolated && testClasses.size <= 1 && null !in testClasses) MODE_ISOLATED else MODE_SHARED
        return WorkerRecords(lines, positions, rows(first), rows(named), Tsv.join(jvm, mode), unreadUnowned)
    }

    /**
     * Feeds [touch] what the worker's JVM loaded or read, by the record it preceded, and
     * [touchNamed] the lookups and reads after discovery. Anything this cannot vouch for (no file,
     * no end marker, no plan start, a jar it cannot list, an observation the agent said was
     * incomplete) touches every class from that point, never none.
     */
    private fun readTouches(
        worker: File,
        inScope: (String) -> Boolean,
        touch: (String, Int) -> Unit,
        touchNamed: (String, Int) -> Unit,
    ) {
        val both = { name: String, sequence: Int -> touch(name, sequence); touchNamed(name, sequence) }
        val file = File(worker, TOUCHES_FILE)
        val lines = runCatching { if (file.isFile) file.readLines() else null }.getOrNull()
        if (lines == null || lines.lastOrNull { it.isNotBlank() } != TOUCHES_COMPLETE) {
            both(FIRST_TOUCH_ANY, 0)
            return
        }
        // Discovery loads and inspects every test class; what it did stays out of the named touches.
        var planStarted = false
        for (line in lines) {
            if (line.isBlank() || line == TOUCHES_COMPLETE) continue
            val parts = Tsv.split(line)
            val sequence = parts.getOrNull(0)?.toIntOrNull()
            if (parts.size != 3 || sequence == null) {
                both(FIRST_TOUCH_ANY, 0)
                return
            }
            val named = { name: String -> if (inScope(name)) { touch(name, sequence); if (planStarted) touchNamed(name, sequence) } }
            when (parts[1]) {
                TOUCH_PLAN_STARTED -> planStarted = true
                TOUCH_LOADED -> if (inScope(parts[2])) touch(parts[2], sequence)
                TOUCH_DEFINED -> named(parts[2])
                TOUCH_LOOKUP -> if (parts[2] == FIRST_TOUCH_ANY) touchNamed(FIRST_TOUCH_ANY, if (planStarted) sequence else 0)
                    else named(binaryName(parts[2]))
                TOUCH_READ -> namesForPath(parts[2]).forEach(named)
                TOUCH_JAR -> {
                    val entries = runCatching {
                        ZipFile(parts[2]).use { zip -> zip.entries().toList().map { it.name } }
                    }.getOrNull()
                    if (entries == null) {
                        both(FIRST_TOUCH_ANY, sequence)
                    } else {
                        entries.flatMap(::namesForPath).forEach(named)
                    }
                }
                TOUCH_ALL -> both(FIRST_TOUCH_ANY, sequence)
                else -> both(FIRST_TOUCH_ANY, sequence)
            }
        }
        if (!planStarted) touchNamed(FIRST_TOUCH_ANY, 0)
    }

    /**
     * `Foo`, `[Lcom.acme.Foo;`, `Lcom/acme/Foo;` or `com/acme/Foo` as the binary name
     * `com.acme.Foo`. Only a descriptor, a leading `L` with a trailing `;`, is unwrapped: `LoginTest`
     * in the default package is a name, not a descriptor.
     */
    private fun binaryName(name: String): String {
        val element = name.trimStart('[')
        val unwrapped = if (element.startsWith("L") && element.endsWith(";")) {
            element.substring(1, element.length - 1)
        } else {
            element
        }
        return unwrapped.replace('/', '.')
    }

    /**
     * Every class name a class or source file's path could stand for: each suffix of its directory
     * path, since where the class root ends is not recorded. The scope filter keeps the real one,
     * and a spare name can only select more.
     */
    internal fun namesForPath(path: String): List<String> {
        val normalised = path.replace('\\', '/')
        val extension = listOf(".class", ".java", ".kt", ".groovy", ".scala").firstOrNull(normalised::endsWith)
            ?: return emptyList()
        val segments = normalised.removeSuffix(extension).split('/').filter(String::isNotEmpty)
        return segments.indices.map { segments.subList(it, segments.size).joinToString(".") }
    }

    /**
     * The merged map's positions, first touches, named touches and JVM modes: this capture's, plus
     * those of every retained test and of the JVMs they ran in. A capture that dates the map
     * retains no test, so it keeps only its own. Sorted, so an unchanged map writes the same bytes.
     */
    private fun mergeOrder(
        mapDir: File,
        recaptured: Set<String>,
        decoded: List<WorkerRecords>,
        datesTheMap: Boolean,
    ): List<List<String>> {
        val current = File(mapDir, MAP_SCHEMA_VERSION_FILE).takeIf(File::isFile)?.readText()?.trim() ==
            "$MAP_SCHEMA_VERSION"
        val existing = { name: String ->
            if (!current) emptyList() else File(mapDir, name).takeIf(File::isFile)?.readLines()
                ?.filter(String::isNotBlank).orEmpty()
        }
        val retainedPositions = if (datesTheMap) {
            emptyList()
        } else {
            existing(POSITIONS_FILE).filter { Tsv.split(it).last() !in recaptured }
        }
        val retainedJvms = retainedPositions.mapTo(HashSet()) { Tsv.split(it).first() }
        val retained = { name: String -> existing(name).filter { Tsv.split(it).first() in retainedJvms } }
        return listOf(
            (retainedPositions + decoded.flatMap(WorkerRecords::positions)).distinct().sorted(),
            (retained(FIRST_TOUCH_FILE) + decoded.flatMap(WorkerRecords::firstTouches)).distinct().sorted(),
            (retained(NAMED_TOUCH_FILE) + decoded.flatMap(WorkerRecords::namedTouches)).distinct().sorted(),
            (retained(JVM_MODE_FILE) + decoded.map(WorkerRecords::mode)).distinct().sorted(),
        )
    }

    /** Class names with at least one executed probe; loaded-but-unexecuted does not count. */
    private fun coveredClasses(exec: File): Set<String>? {
        // Absent or empty means nothing touched; unreadable returns null.
        if (!exec.isFile || exec.length() == 0L) return emptySet()
        val store = ExecutionDataStore()
        return runCatching {
            exec.inputStream().buffered().use { input ->
                ExecutionDataReader(input).apply {
                    setExecutionDataVisitor(store::put)
                    setSessionInfoVisitor(SessionInfoStore()::visitSessionInfo)
                    read()
                }
            }
            store.contents.filter(ExecutionData::hasHits).map { it.name.replace('/', '.') }.toSet()
        }.getOrNull()
    }

    /** Stamps the capture commit, or removes it when unknown: a stale one hides later changes. */
    private fun writeCaptureCommit(mapDir: File, commit: String?, datesTheMap: Boolean) {
        val file = File(mapDir, CAPTURE_COMMIT_FILE)
        // Only a run that observed the whole task may date the map; a partial run would declare
        // its untouched records current and switch off the widening that protects them.
        if (!datesTheMap) {
            return
        }
        if (commit.isNullOrBlank()) {
            file.delete()
            return
        }
        writeAtomically(file, commit.trim() + "\n")
    }
}
