package io.github.zeuspizza.yoriwake.maven

import io.github.zeuspizza.yoriwake.agent.contract.AgentContract
import io.github.zeuspizza.yoriwake.agent.select.FilterRule
import io.github.zeuspizza.yoriwake.agent.select.Selector
import io.github.zeuspizza.yoriwake.gradle.capture.MapLocation
import io.github.zeuspizza.yoriwake.gradle.change.SelectingStep
import io.github.zeuspizza.yoriwake.gradle.report.PathIgnoreReport
import io.github.zeuspizza.yoriwake.gradle.report.selectionShare
import io.github.zeuspizza.yoriwake.gradle.report.writeExplanation
import io.github.zeuspizza.yoriwake.gradle.report.writeUnanswered
import org.apache.maven.execution.MavenSession
import org.apache.maven.plugin.AbstractMojo
import org.apache.maven.plugins.annotations.LifecyclePhase
import org.apache.maven.plugins.annotations.Mojo
import org.apache.maven.plugins.annotations.Parameter
import org.apache.maven.plugins.annotations.ResolutionScope
import org.apache.maven.project.MavenProject
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.util.Properties

/**
 * `mvn test-compile yoriwake:explain`: what `-Dyoriwake.select` would decide for each Surefire and
 * Failsafe execution of a module, as Gradle's `yoriwakeExplain` does, without running a test. It
 * takes the selecting run's steps on a copy of the map, so the map and its markers stay as they
 * were, and writes `explain.json` beside the map. `-Dyoriwake.execution=<id>` explains one execution id.
 */
@Mojo(name = "explain", defaultPhase = LifecyclePhase.NONE, requiresDependencyResolution = ResolutionScope.TEST, threadSafe = true)
class ExplainMojo : AbstractMojo() {

    @Parameter(defaultValue = "\${session}", readonly = true, required = true)
    private lateinit var session: MavenSession

    @Parameter(defaultValue = "\${project}", readonly = true, required = true)
    private lateinit var project: MavenProject

    /** Only the executions with this id; every one when unset. */
    @Parameter(property = "yoriwake.execution")
    private var execution: String? = null

    override fun execute() {
        val root = (session.request.multiModuleProjectDirectory ?: session.topLevelProject.basedir).absoluteFile
        val home = File(root, Recording.MAPS_HOME)
        val classpath = runCatching { project.testClasspathElements.map(::File) }.getOrDefault(emptyList())
        val selection = Selection(session, root, consoleLog)
        for (each in TestExecutions.of(session, listOf(project)).filter { execution == null || it.id == execution }) {
            explain(each, MapLocation.forTask(home, each.key), root, classpath, selection)
        }
    }

    private fun explain(each: TestExecutions.Found, mapDir: File, root: File, classpath: List<File>, selection: Selection) {
        val name = each.name
        // Declined as the build would decline it, in the same order, before any selection step.
        val agentGoal = each.ownAgentGoal.takeIf { JacocoAdoption.executions(project, it).isNotEmpty() }
            ?: JacocoAdoption.PREPARE_AGENT
        val declined = DevelocityDetection.decline(project, each.execution, session)
            ?: Declines.of(project, each.plugin, each.execution, session)
            ?: BuildCache.decline(session)
            ?: JacocoAdoption.decline(project, agentGoal, session)
        if (declined != null) {
            val reason = "declined, ${declined.kind.token}: ${declined.detail}"
            consoleLog.info("[yoriwake] $name would run everything: $reason")
            writeUnanswered(mapDir, name, "", Selector.Decision.FullRunKind.DAEMON_REFUSED, reason, refusalKind = declined.kind.token)
            return
        }
        // The selecting steps write the change set and the capture markers beside the map they read.
        val copy = Files.createTempDirectory("yoriwake-explain").toFile()
        try {
            if (mapDir.isDirectory) mapDir.copyRecursively(copy, overwrite = true)
            val properties = Properties().apply { setProperty(AgentContract.MAP_DIR_PROPERTY, copy.path) }
            var worked: Pair<SelectingStep.Worked, String>? = null
            selection.apply(
                name, copy, project.basedir.relativeTo(root).invariantSeparatorsPath,
                classpathView(session, root, project, classpath),
                environment = null, undated = null, isolatedCapture = false,
                stamp = { error("an explanation writes no selection record") },
                stopsAfterFailure = true,
                properties = properties,
                explaining = true,
                listedName = mapDir.name,
                worked = { done, base -> worked = done to base },
            )
            val refused = properties.getProperty(AgentContract.REFUSED_PROPERTY)
            val done = worked
            if (refused != null || done == null) {
                val reason = refused ?: "selection decided nothing for it"
                consoleLog.info("[yoriwake] $name would run everything: $reason")
                writeUnanswered(
                    mapDir, name, done?.second.orEmpty(), Selector.Decision.FullRunKind.DAEMON_REFUSED, reason,
                    refusalKind = properties.getProperty(AgentContract.REFUSED_KIND_PROPERTY),
                )
                return
            }
            val (step, base) = done
            val decision = FilterRule.decisionFrom(FilterRule.resolveInputs { properties.getProperty(it) }, copy)
            writeExplanation(
                mapDir, name, base, step.scoped, step.established, decision, step.widening, step.capture, step.forcing,
                ignoredEvidence = PathIgnoreReport.evidence(step.scoped.ignore, mapDir),
            )
            if (decision.isFullRun) {
                consoleLog.info("[yoriwake] $name would run everything: ${decision.fullRunReason()}")
            } else {
                val known = decision.knownTests()
                val selected = decision.selectedCount()
                consoleLog.info("[yoriwake] $name would run $selected of $known known tests ${selectionShare(selected, known)}")
            }
        } finally {
            copy.deleteRecursively()
        }
    }

    private companion object {
        val consoleLog = LoggerFactory.getLogger(ExplainMojo::class.java)
    }
}
