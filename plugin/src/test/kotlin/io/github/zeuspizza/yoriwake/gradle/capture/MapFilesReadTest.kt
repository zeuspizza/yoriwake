package io.github.zeuspizza.yoriwake.gradle.capture

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// A map file read to decide what runs, but left out of the digest, is one a restored cache can
// change without the trusted-map list noticing.
class MapFilesReadTest {

    /** Named in a `File(mapDir, …)` in the plugin or agent and deliberately not digested, each with why. */
    private val excluded = mapOf(
        "CHANGE_SET_FILE" to "rewritten by every selecting run before its tests start",
        "RAW_DIR" to "the run's own records, cleared before it starts",
        "DECISIONS_FILE" to "written by the run, read by nothing that decides",
        "EXPLANATION_FILE" to "a report",
        "AUDIT_FILE" to "a report",
        "OBSERVATION_FILE" to "an observing run's report, read by nothing that decides",
        "FILE" to "the audit's task facts, a report",
        "TASK_FILE" to "the payback report",
        "PENDING_FILE" to "the start reading of HEAD, the run's own",
        "STATS_PENDING_FILE" to "the start reading of the tree, the run's own",
        "\"loaded\"" to "the agent's per-worker raw lists, folded in by the decode",
        "MAP_DIGEST_FILE" to "the digest itself, never compared with the list",
        "MOVED_FILE" to "explains a forced run, read by nothing that decides",
        "name" to "a name drawn from constants this scan sees elsewhere",
    )

    private val digestedByName = mapOf(
        "COVERAGE_FILE" to "coverage.tsv", "POSITIONS_FILE" to "positions.tsv",
        "FIRST_TOUCH_FILE" to "first-touch.tsv", "NAMED_TOUCH_FILE" to "named-touch.tsv",
        "JVM_MODE_FILE" to "jvm-mode.tsv", "SCOPE_FILE" to "scope",
        "EFFECTIVE_SCOPE_FILE" to "effective-scope", "LOADED_FILE" to "loaded.txt",
        "LOADED_PROVENANCE_FILE" to "loaded-provenance", "LOADED_SCOPE_FILE" to "loaded-scope",
        "CONSTANTS_FILE" to "constants", "CLASS_DIGESTS_FILE" to "class-digests",
        "ANNOTATION_DIGESTS_FILE" to "annotation-digests", "CAPTURE_COMMIT_FILE" to "capture-commit",
        "RESOURCE_DIGESTS_FILE" to "resource-digests",
        "SNAPSHOT_FILE" to "worktree-snapshot", "MAP_SCHEMA_VERSION_FILE" to "schema-version",
    )

    private val mapFileUse = Regex("""File\(\s*mapDir\s*,\s*([A-Za-z_.]+|"[^"]*")""")

    private fun sources(): List<File> =
        listOf(File("src/main/kotlin"), File("../agent/src/main/java"))
            .flatMap { root -> root.walkTopDown().filter { it.extension in setOf("kt", "java") }.toList() }

    @Test
    fun `every map file a source names is digested or named here as excluded`() {
        val sources = sources()
        assertTrue(sources.size > 50, "found only ${sources.size} sources; run from the plugin directory")
        // MapProvenance names its files through DIGESTED itself.
        val unnamed = sources.filter { it.name != "MapProvenance.kt" }.flatMap { file ->
            mapFileUse.findAll(file.readText()).map { it.groupValues[1].substringAfterLast('.') }
                .filter { it !in digestedByName && it !in excluded }
                .map { "${file.name}: $it" }
                .toList()
        }
        assertEquals(emptyList(), unnamed, "a map file neither digested nor excluded")
    }

    @Test
    fun `the digested list is exactly the files the decode writes`() {
        assertEquals(digestedByName.values.sorted(), MapProvenance.DIGESTED)
    }
}
