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
import org.gradle.api.tasks.testing.TestDescriptor
import org.gradle.api.tasks.testing.TestListener
import org.gradle.api.tasks.testing.TestResult
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
 * test JVM starts with, and leaves no record when fail-fast is on by now or once its tests start; a
 * complement whose configuration differs from that one runs every test. A run in which the build
 * placed an action of its own between this one and the task's own runs every test too: nothing the
 * plugin reads here is final then ([afterLastLook]). [agentArgument] is the plugin's own
 * `-javaagent` argument, which the configuration leaves out.
 */
internal class LastLook(private val mapDir: File, private val agentArgument: String?) : Action<Task> {

    override fun execute(task: Task) {
        val test = task as Test
        if (declinedUnderDevelocity(test)) return
        val selecting = test.systemProperties[AgentContract.SELECT_PROPERTY]?.toString() == "true"
        val complementing = test.systemProperties[AgentContract.COMPLEMENT_RECORD_PROPERTY] != null
        if (!selecting && !complementing) return
        val late = runCatching { afterLastLook(test.actions.map { (it as? Describable)?.displayName }) }.getOrDefault(emptyList())
        if (selecting) {
            // A run that runs everything leaves no record.
            if (late.isEmpty()) finishStart(test) else runCatching { File(mapDir, SelectionRecord.START_FILE).delete() }
        }
        val handed = handOver(mapDir)
        val configured = if (complementing && late.isEmpty()) {
            val copy = runCatching { File(test.systemProperties[AgentContract.COMPLEMENT_RECORD_PROPERTY].toString()).readText() }
                .getOrNull()
            configurationMoved(copy) { configurationOf(test, agentArgument) }
        } else {
            null
        }
        if (configured != null || late.isNotEmpty()) runCatching { handed.delete() }
        val moved = configured ?: run {
            if (late.isNotEmpty()) return@run lateActions(late)
            if (!handed.isFile) return
            val compared = runCatching { read(handed.readText()) }.getOrNull()
            runCatching { handed.delete() }
            whatMoved(compared, test.classpath.files) { again(test.classpath.files, it) }
        } ?: return
        // Left out by its record no more: the run is a full run that captures nothing.
        if (complementing) test.setSystemProperties(test.systemProperties - AgentContract.COMPLEMENT_RECORD_PROPERTY)
        val (kind, reason) = when {
            late.isNotEmpty() -> RefusalKind.LATE_TASK_ACTION to "$moved. Add such an action with doFirst instead, which runs before that look"
            complementing -> RefusalKind.COMPLEMENT_RECORD_MISMATCH to "$moved. The selecting run's record cannot vouch for it"
            else -> RefusalKind.CLASSPATH_FILES_CHANGED to "$moved. No map records what such an action writes; " +
                "generate the file in a task the test task depends on instead"
        }
        test.logger.lifecycle("[yoriwake] ${test.path}: $reason. The whole suite runs.")
        refuse(test, kind, reason)
        // Never read under a refusal; deleted so it is not mistaken for this run's.
        runCatching { File(mapDir, AgentContract.CHANGE_SET_FILE).delete() }
    }

    /**
     * A selecting run's start, given the configuration its test JVM starts with; none when the
     * task's own `doFirst` actions turned fail-fast on, as at the start, or an action after this one
     * did (see [FailFastOnceStarted]).
     */
    private fun finishStart(test: Test) {
        if (!File(mapDir, SelectionRecord.START_FILE).isFile) return
        if (test.failFast) {
            runCatching { File(mapDir, SelectionRecord.START_FILE).delete() }
            test.logger.info("[yoriwake] ${test.path}: --fail-fast drops the results of tests that finish after the first failure, so this run leaves no selection record")
            return
        }
        val failure = addLateConfiguration(mapDir) { configurationOf(test, agentArgument) }
        if (failure != null) {
            test.logger.info("[yoriwake] ${test.path}: the selection record's start could not be completed ($failure), so this run leaves no selection record")
            return
        }
        test.addTestListener(FailFastOnceStarted(test, mapDir))
    }

    /**
     * Drops a selecting run's start when its tests begin with fail-fast on: an action the build
     * placed between this one and the task's own can still turn it on, and none can once the tests
     * have started.
     */
    private class FailFastOnceStarted(private val test: Test, private val mapDir: File) : TestListener {
        override fun beforeSuite(suite: TestDescriptor) {
            if (suite.parent != null || !test.failFast) return
            if (runCatching { File(mapDir, SelectionRecord.START_FILE).delete() }.getOrDefault(false)) {
                test.logger.info("[yoriwake] ${test.path}: --fail-fast drops the results of tests that finish after the first failure, so this run leaves no selection record")
            }
        }

        override fun afterSuite(suite: TestDescriptor, result: TestResult) {}

        override fun beforeTest(testDescriptor: TestDescriptor) {}

        override fun afterTest(testDescriptor: TestDescriptor, result: TestResult) {}
    }

    internal class Compared(
        val rootDir: File,
        val buildDirs: List<String>,
        val classes: Boolean,
        val digests: Map<String, String>,
        /** [ClasspathFiles.table]'s homes when the comparing action compared a table; empty for a walk. */
        val homes: Map<String, File> = emptyMap(),
        /** The Gradle user home a walk took Gradle's module cache from; null for a table or none. */
        val gradleUserHome: File? = null,
        /** [ClasspathFiles.table]'s project artifacts when the comparing action compared a table. */
        val projectArtifacts: Set<File> = emptySet(),
        /** The build's `stampedFiles`, which the comparing action left out of what moved. */
        val stamped: List<String> = emptyList(),
        /** The build's runtime classpath normalization, which the comparing action applied. */
        val normalization: RuntimeNormalization? = null,
    )

    companion object {
        /** The name it runs under, which [moveLast] finds it by. */
        const val NAME = "yoriwake last look"

        /** The name the task's own action runs under. */
        private const val OWN = "Execute executeTests"

        /**
         * The actions between this one and the task's own, given the display name of each of the
         * task's actions in order (null for one that has none): the build placed them there after
         * [moveLast] ran, and whatever they set or write, the looks before them did not see. Every
         * action after this one when the task's own is not among them, as when the build wrapped
         * it; empty when this one cannot be found, as under a build that wraps every action.
         */
        internal fun afterLastLook(names: List<String?>): List<String> {
            val look = names.indexOf("Execute $NAME")
            val own = names.indexOf(OWN)
            if (look < 0 || own in 0 until look) return emptyList()
            return names.subList(look + 1, if (own < 0) names.size else own).map { it ?: "an action without a name" }
        }

        /** Why a run with [late] between this action and the task's own may not leave tests out. */
        private fun lateActions(late: List<String>) =
            "${late.size} of the test task's actions (${late.distinct().joinToString()}) run after yoriwake last looked at " +
                "the task's configuration and its classpath files, and may change either unseen"

        /** Where a comparing action leaves what it compared, for this action to look at again. */
        fun handOver(mapDir: File) = File(mapDir, "classpath-files.compared")

        /**
         * [classpath] digested as the comparing action did: a selecting run's table, with its
         * dependencies, when the hand-over carries homes; else a complement's walk of build output.
         */
        internal fun again(classpath: Collection<File>, compared: Compared): ClasspathFiles.Walk =
            if (compared.homes.isEmpty()) ClasspathFiles.walk(
                classpath, compared.buildDirs, compared.rootDir, classes = compared.classes,
                gradleUserHome = compared.gradleUserHome,
            )
            else ClasspathFiles.table(
                classpath, compared.buildDirs, compared.rootDir, compared.homes, projectArtifacts = compared.projectArtifacts,
            )

        /**
         * Why the run may no longer leave tests out, given what a comparing action handed over
         * ([compared], null when it could not be read) and [walk] of the classpath now; null when
         * nothing moved. Anything that cannot be established forces.
         */
        internal fun whatMoved(
            compared: Compared?,
            /** The classpath [walk] digests, whose directories the normalization's paths are taken within. */
            classpath: Collection<File> = emptyList(),
            walk: (Compared) -> ClasspathFiles.Walk,
        ): String? {
            if (compared == null) {
                return "the classpath files it compared could not be read back before the test JVM started"
            }
            return when (val now = runCatching { walk(compared) }.getOrElse { ClasspathFiles.Walk.Refused(it.toString()) }) {
                is ClasspathFiles.Walk.Refused ->
                    "this task's classpath files could not be digested again before the test JVM started (${now.reason})"
                is ClasspathFiles.Walk.Found -> ClasspathFiles.compare(compared.digests, now.digests)
                    .let { moved -> ClasspathFiles.Moved(moved.files - ClasspathFiles.stamped(moved, compared.stamped, compared.rootDir)) }
                    .let { moved ->
                        val filters = compared.normalization?.filters()
                        ClasspathFiles.Moved(
                            moved.files - ClasspathFiles.normalized(moved, compared.digests, now.digests, filters, classpath, compared.rootDir)
                        )
                    }
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
                val own = actions.indexOfFirst { (it as? Describable)?.displayName == OWN }
                val look = actions.indexOfFirst { (it as? Describable)?.displayName == "Execute $NAME" }
                if (own < 0 || look < 0 || look == own - 1) return
                val moved = actions.removeAt(look)
                actions.add(if (look < own) own - 1 else own, moved)
            }
        }

        /**
         * Written by a comparing action, with the walk's [rootDir], [buildDirs] and [classes], and
         * the table's [homes] and [projectArtifacts] when it compared one, or the walk's [gradleUserHome],
         * and the build's [stamped] files and [normalization].
         */
        fun write(
            mapDir: File,
            rootDir: File,
            buildDirs: Collection<String>,
            classes: Boolean,
            digests: Map<String, String>,
            homes: Map<String, File> = emptyMap(),
            gradleUserHome: File? = null,
            projectArtifacts: Set<File> = emptySet(),
            stamped: List<String> = emptyList(),
            normalization: RuntimeNormalization? = null,
        ) {
            val lines = listOf("root\t${Tsv.escape(rootDir.absolutePath)}", "classes\t$classes") +
                buildDirs.map { "build\t${Tsv.escape(it)}" } +
                homes.map { (name, home) -> "home\t${Tsv.escape(name)}\t${Tsv.escape(home.absolutePath)}" } +
                listOfNotNull(gradleUserHome?.let { "gradle-user-home\t${Tsv.escape(it.absolutePath)}" }) +
                projectArtifacts.map { "project\t${Tsv.escape(it.absolutePath)}" } +
                stamped.map { "stamped\t${Tsv.escape(it)}" } +
                normalization?.resources.orEmpty().map { "normalized-resource\t${Tsv.escape(it)}" } +
                normalization?.attributes.orEmpty().map { "normalized-attribute\t${Tsv.escape(it)}" } +
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
            val homes = mutableMapOf<String, File>()
            var gradleUserHome: File? = null
            val projectArtifacts = mutableSetOf<File>()
            val stamped = mutableListOf<String>()
            val resources = mutableListOf<String>()
            val attributes = mutableListOf<String>()
            for (line in lines.dropLast(1)) {
                val parts = line.split('\t')
                when {
                    parts.size == 2 && parts[0] == "root" -> root = File(Tsv.unescape(parts[1]))
                    parts.size == 2 && parts[0] == "classes" -> classes = parts[1].toBooleanStrictOrNull() ?: return null
                    parts.size == 2 && parts[0] == "build" -> buildDirs += Tsv.unescape(parts[1])
                    parts.size == 3 && parts[0] == "file" -> digests[Tsv.unescape(parts[1])] = parts[2]
                    parts.size == 3 && parts[0] == "home" -> homes[Tsv.unescape(parts[1])] = File(Tsv.unescape(parts[2]))
                    parts.size == 2 && parts[0] == "gradle-user-home" -> gradleUserHome = File(Tsv.unescape(parts[1]))
                    parts.size == 2 && parts[0] == "project" -> projectArtifacts += File(Tsv.unescape(parts[1]))
                    parts.size == 2 && parts[0] == "stamped" -> stamped += Tsv.unescape(parts[1])
                    parts.size == 2 && parts[0] == "normalized-resource" -> resources += Tsv.unescape(parts[1])
                    parts.size == 2 && parts[0] == "normalized-attribute" -> attributes += Tsv.unescape(parts[1])
                    else -> return null
                }
            }
            val normalization = if (resources.isEmpty() && attributes.isEmpty()) null else RuntimeNormalization(resources, attributes)
            return Compared(
                root ?: return null, buildDirs, classes ?: return null, digests, homes, gradleUserHome, projectArtifacts, stamped,
                normalization,
            )
        }

        private const val END = "end"
    }
}
