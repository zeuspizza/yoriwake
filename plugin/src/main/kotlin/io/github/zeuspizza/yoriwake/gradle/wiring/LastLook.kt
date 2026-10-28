package io.github.zeuspizza.yoriwake.gradle.wiring

import io.github.zeuspizza.yoriwake.agent.contract.AgentContract
import io.github.zeuspizza.yoriwake.agent.contract.Tsv
import io.github.zeuspizza.yoriwake.gradle.capture.SelectionRecord
import io.github.zeuspizza.yoriwake.gradle.capture.writeAtomically
import io.github.zeuspizza.yoriwake.gradle.change.ClasspathFiles
import io.github.zeuspizza.yoriwake.gradle.change.RefusalKind
import org.gradle.api.Action
import org.gradle.api.Describable
import org.gradle.api.Task
import org.gradle.api.tasks.testing.Test
import java.io.File

/**
 * The classpath files a run that leaves tests out compared, and the task's configuration, looked at
 * again right before the test JVM starts.
 *
 * The plugin wires a `Test` task once every build script has run, and `doFirst` prepends, so its
 * comparisons run before every `doFirst` the build itself adds: a build-info file the build stamps
 * from git there is written after them. This action runs after those: registered when the plugin is
 * applied, and moved next to the task's own action once every build script has run (see [moveLast]).
 * When the run still narrows, or still leaves out what a selecting run ran, and a file moved since
 * it was compared, the whole suite runs. A selecting run's record also carries the configuration its
 * test JVM starts with, and leaves no record when fail-fast is on by now; a complement whose
 * configuration differs from that one runs every test. [agentArgument] is the plugin's own
 * `-javaagent` argument, which the configuration leaves out.
 */
internal class LastLook(private val mapDir: File, private val agentArgument: String?) : Action<Task> {

    override fun execute(task: Task) {
        val test = task as Test
        if (declinedUnderDevelocity(test)) return
        val selecting = test.systemProperties[AgentContract.SELECT_PROPERTY]?.toString() == "true"
        val complementing = test.systemProperties[AgentContract.COMPLEMENT_RECORD_PROPERTY] != null
        if (!selecting && !complementing) return
        if (selecting) finishStart(test)
        val handed = handOver(mapDir)
        val configured = if (complementing) {
            val copy = runCatching { File(test.systemProperties[AgentContract.COMPLEMENT_RECORD_PROPERTY].toString()).readText() }
                .getOrNull()
            configurationMoved(copy) { configurationOf(test, agentArgument) }
        } else {
            null
        }
        if (configured != null) runCatching { handed.delete() }
        val moved = configured ?: run {
            if (!handed.isFile) return
            val compared = runCatching { read(handed.readText()) }.getOrNull()
            runCatching { handed.delete() }
            whatMoved(compared) {
                ClasspathFiles.walk(test.classpath.files, it.buildDirs, it.rootDir, classes = it.classes)
            }
        } ?: return
        val (kind, reason) = if (complementing) {
            // Left out by its record no more: the run is a full run that captures nothing.
            test.setSystemProperties(test.systemProperties - AgentContract.COMPLEMENT_RECORD_PROPERTY)
            RefusalKind.COMPLEMENT_RECORD_MISMATCH to "$moved. The selecting run's record cannot vouch for it"
        } else {
            RefusalKind.CLASSPATH_FILES_CHANGED to "$moved. No map records what such an action writes; " +
                "generate the file in a task the test task depends on instead"
        }
        test.logger.lifecycle("[yoriwake] ${test.path}: $reason. The whole suite runs.")
        refuse(test, kind, reason)
        // Never read under a refusal; deleted so it is not mistaken for this run's.
        runCatching { File(mapDir, AgentContract.CHANGE_SET_FILE).delete() }
    }

    /**
     * A selecting run's start, given the configuration its test JVM starts with; none when the
     * task's own `doFirst` actions turned fail-fast on, as at the start.
     */
    private fun finishStart(test: Test) {
        if (!File(mapDir, SelectionRecord.START_FILE).isFile) return
        if (test.failFast) {
            runCatching { File(mapDir, SelectionRecord.START_FILE).delete() }
            test.logger.info("[yoriwake] ${test.path}: --fail-fast drops the results of tests that finish after the first failure, so this run leaves no selection record")
            return
        }
        addLateConfiguration(mapDir) { configurationOf(test, agentArgument) }
            ?.let { test.logger.info("[yoriwake] ${test.path}: the selection record's start could not be completed ($it), so this run leaves no selection record") }
    }

    internal class Compared(
        val rootDir: File,
        val buildDirs: List<String>,
        val classes: Boolean,
        val digests: Map<String, String>,
    )

    companion object {
        /** The name it runs under, which [moveLast] finds it by. */
        const val NAME = "yoriwake last look"

        /** Where a comparing action leaves what it compared, for this action to look at again. */
        fun handOver(mapDir: File) = File(mapDir, "classpath-files.compared")

        /**
         * Why the run may no longer leave tests out, given what a comparing action handed over
         * ([compared], null when it could not be read) and [walk] of the classpath now; null when
         * nothing moved. Anything that cannot be established forces.
         */
        internal fun whatMoved(compared: Compared?, walk: (Compared) -> ClasspathFiles.Walk): String? {
            if (compared == null) {
                return "the classpath files it compared could not be read back before the test JVM started"
            }
            return when (val now = runCatching { walk(compared) }.getOrElse { ClasspathFiles.Walk.Refused(it.toString()) }) {
                is ClasspathFiles.Walk.Refused ->
                    "this task's classpath files could not be digested again before the test JVM started (${now.reason})"
                is ClasspathFiles.Walk.Found -> ClasspathFiles.compare(compared.digests, now.digests)
                    .takeUnless { it.isEmpty }
                    ?.let {
                        "build output on this task's classpath changed after it was compared, while the task's " +
                            "own doFirst actions ran: ${it.named()}"
                    }
            }
        }

        /**
         * Adds [late], the configuration the test JVM starts with, to the selection record's start in
         * [mapDir]; a start that cannot be read, decoded or written again is deleted, so the run leaves
         * no record. Returns why it was deleted, or null.
         */
        internal fun addLateConfiguration(mapDir: File, late: () -> String): String? {
            val file = File(mapDir, SelectionRecord.START_FILE)
            val failure = runCatching {
                val start = requireNotNull(SelectionRecord.Start.decode(file.readText())) { "it could not be decoded" }
                SelectionRecord.writeStart(mapDir, SelectionRecord.Start(start.token, start.stamp, late()))
            }.exceptionOrNull() ?: return null
            runCatching { file.delete() }
            return failure.toString()
        }

        /**
         * Why a complement run whose test JVM starts with the configuration [now] may no longer leave
         * out what its record ([record], the copy's text; null when it could not be read) lists; null
         * when the record's own test JVM started with the same one. Anything that cannot be
         * established forces.
         */
        internal fun configurationMoved(record: String?, now: () -> String): String? {
            val recorded = record?.let(SelectionRecord::read)?.takeIf { it.failure() == null }
                ?.let(SelectionRecord::lateConfigurationOf)
                ?: return "the selection record names no configuration its test JVM started with"
            val digest = runCatching(now).getOrElse {
                return "the task's configuration could not be read again before the test JVM started ($it)"
            }
            return if (digest == recorded) {
                null
            } else {
                "the task's configuration (its system properties, JVM arguments or test filters) as its test JVM " +
                    "starts differs from the selecting run's, set by the task's own doFirst actions"
            }
        }

        /**
         * Moves this action next to the task's own action, after every `doFirst` that exists by now,
         * such as one a root `subprojects {}` block or a plugin applied earlier added. Left where it
         * is when either cannot be found.
         */
        fun moveLast(test: Test) {
            runCatching {
                val actions = test.actions
                val own = actions.indexOfFirst { (it as? Describable)?.displayName == "Execute executeTests" }
                val look = actions.indexOfFirst { (it as? Describable)?.displayName == "Execute $NAME" }
                if (own < 0 || look < 0 || look == own - 1) return
                val moved = actions.removeAt(look)
                actions.add(if (look < own) own - 1 else own, moved)
            }
        }

        /** Written by a comparing action, with the walk's [rootDir], [buildDirs] and [classes]. */
        fun write(mapDir: File, rootDir: File, buildDirs: Collection<String>, classes: Boolean, digests: Map<String, String>) {
            val lines = listOf("root\t${Tsv.escape(rootDir.absolutePath)}", "classes\t$classes") +
                buildDirs.map { "build\t${Tsv.escape(it)}" } +
                digests.map { (path, digest) -> "file\t${Tsv.escape(path)}\t$digest" }
            writeAtomically(handOver(mapDir), lines.joinToString("\n", postfix = "\n$END\n"))
        }

        /** What [write] wrote, or null for anything else, which forces. */
        internal fun read(text: String): Compared? {
            val lines = text.removeSuffix("\n").split('\n')
            if (lines.lastOrNull() != END) return null
            var root: File? = null
            var classes: Boolean? = null
            val buildDirs = mutableListOf<String>()
            val digests = mutableMapOf<String, String>()
            for (line in lines.dropLast(1)) {
                val parts = line.split('\t')
                when {
                    parts.size == 2 && parts[0] == "root" -> root = File(Tsv.unescape(parts[1]))
                    parts.size == 2 && parts[0] == "classes" -> classes = parts[1].toBooleanStrictOrNull() ?: return null
                    parts.size == 2 && parts[0] == "build" -> buildDirs += Tsv.unescape(parts[1])
                    parts.size == 3 && parts[0] == "file" -> digests[Tsv.unescape(parts[1])] = parts[2]
                    else -> return null
                }
            }
            return Compared(root ?: return null, buildDirs, classes ?: return null, digests)
        }

        private const val END = "end"
    }
}
