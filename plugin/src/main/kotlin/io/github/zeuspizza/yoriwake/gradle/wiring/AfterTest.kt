package io.github.zeuspizza.yoriwake.gradle.wiring

import io.github.zeuspizza.yoriwake.agent.contract.AgentContract
import io.github.zeuspizza.yoriwake.agent.contract.Tsv
import org.gradle.api.logging.Logger
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.testing.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Work that must follow a `Test` task's run, whether its tests passed or failed.
 *
 * On a passing run it runs in the task's own `doLast`, before Gradle snapshots the outputs, so an
 * up-to-date or cached run restores what it wrote. A failed task skips its `doLast`; the marker the
 * `doFirst` wrote then survives, and the decode finalizer runs the same steps from [runIfPending].
 * Each step is held as plain values for the configuration cache, and one that throws does not stop
 * the next.
 */
internal class AfterTest(private val pending: File, private val steps: List<Step>) {

    internal interface Step {
        fun run(taskPath: String, logger: Logger)
    }

    fun attachTo(test: Test) {
        test.doFirst {
            if (declinedUnderDevelocity(test)) return@doFirst
            runCatching {
                pending.parentFile.mkdirs()
                pending.writeText("pending")
            }
        }
        test.doLast {
            if (declinedUnderDevelocity(test)) return@doLast
            run(test.path, test.logger)
        }
    }

    /** Runs the steps only if the task executed and did not reach its own `doLast`. */
    fun runIfPending(taskPath: String, logger: Logger) {
        if (pending.isFile) {
            run(taskPath, logger)
        }
    }

    private fun run(taskPath: String, logger: Logger) {
        steps.forEach { step ->
            runCatching { step.run(taskPath, logger) }.onFailure {
                logger.warn("[yoriwake] $taskPath: an after-test step failed ($it); the run is otherwise unchanged.")
            }
        }
        pending.delete()
    }
}

/**
 * Gives the host's JaCoCo execution file back what this run executed. The agent takes and resets
 * JaCoCo's data around every test, so JaCoCo's own dump at JVM exit holds almost nothing; this run's
 * per-test records hold the rest. An execution file is a sequence of blocks, and a reader merges
 * repeated classes, so appending them is a union and needs no parsing.
 */
internal class RestoreHostCoverage(
    private val recordsDir: File,
    private val destination: Provider<File>,
) : AfterTest.Step {

    override fun run(taskPath: String, logger: Logger) {
        val records = completeRecords(recordsDir)
        // Nothing captured, so JaCoCo's file is already what ran, or the agent was off.
        val target = destination.orNull ?: return
        if (records.isEmpty()) return
        val temporary = File(target.parentFile, target.name + ".yoriwake")
        try {
            target.parentFile.mkdirs()
            if (target.isFile) {
                Files.copy(target.toPath(), temporary.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } else {
                temporary.delete()
            }
            java.io.FileOutputStream(temporary, true).use { out -> records.forEach { out.write(it.readBytes()) } }
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (failure: Exception) {
            temporary.delete()
            logger.warn(
                "[yoriwake] $taskPath: could not add this run's coverage to $target ($failure), so a " +
                    "JaCoCo report of this run reads only what JaCoCo wrote itself."
            )
        }
    }

    internal companion object {
        /**
         * Every record a worker finished writing, in worker and sequence order. The index row is
         * written after its record, so a record a killed JVM left half written has no row, or a
         * row whose length its file does not match.
         */
        fun completeRecords(recordsDir: File): List<File> =
            recordsDir.listFiles { f: File -> f.isDirectory && f.name.startsWith(AgentContract.WORKER_DIR_PREFIX) }
                .orEmpty().sortedBy(File::getName).flatMap { worker ->
                    val index = File(worker, AgentContract.INDEX_FILE)
                    if (!index.isFile) return@flatMap emptyList()
                    index.readLines().mapNotNull { line ->
                        val parts = Tsv.split(line)
                        val sequence = parts.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
                        val length = parts.getOrNull(2)?.toLongOrNull() ?: return@mapNotNull null
                        File(worker, String.format(AgentContract.EXEC_FILE_FORMAT, sequence))
                            .takeIf { parts.size == 5 && it.isFile && it.length() == length }
                    }
                }
    }
}

/** Written when the `Test` task starts and removed by its `doLast`; see [AfterTest]. */
internal fun afterTestPending(recordsDir: File) = File(recordsDir.parentFile, "after-test.pending")
