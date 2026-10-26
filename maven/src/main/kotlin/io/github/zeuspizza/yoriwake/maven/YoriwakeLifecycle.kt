package io.github.zeuspizza.yoriwake.maven

import io.github.zeuspizza.yoriwake.gradle.wiring.ScopeOutcome
import org.apache.maven.AbstractMavenLifecycleParticipant
import org.apache.maven.execution.MavenSession
import org.apache.maven.rtinfo.RuntimeInformation
import org.slf4j.LoggerFactory
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * Where yoriwake joins a Maven build: the POM declares the plugin with `<extensions>true</extensions>`,
 * and Maven calls this once the whole reactor is read, as Gradle's `projectsEvaluated`.
 *
 * Decides, per Surefire and Failsafe execution, whether it can be selected, and scopes the host's
 * JaCoCo agent for those that can. A declined execution is left as the host configured it.
 */
@Named("yoriwake")
@Singleton
internal class YoriwakeLifecycle @Inject constructor(
    private val runtime: RuntimeInformation,
) : AbstractMavenLifecycleParticipant() {

    override fun afterProjectsRead(session: MavenSession) {
        if (!Settings.of(session).enabled) return
        val version = runtime.mavenVersion
        val token = ScopeOutcome.NotApplied.Kind.MAVEN_VERSION.token
        if (!runtime.isMavenVersion("[$MAVEN_FLOOR,)")) {
            log.warn("[yoriwake] $token: Maven $version is older than $MAVEN_FLOOR; yoriwake does nothing in this build")
            return
        }
        if (PRE_RELEASE.matches(version) && !allowsPreRelease(session)) {
            log.warn("[yoriwake] $token: Maven $version is not a release; yoriwake does nothing in this build")
            return
        }
        log.info("[yoriwake] attached to Maven $version")

        val facts = ReactorFacts(session)
        val wired = mutableMapOf<String, ScopeOutcome>()
        val cached = BuildCache.decline(session)
        BuildCache.optOutDevelocity(session)
        for (project in session.projects) {
            val runs = TestExecutions.of(session, listOf(project)).map { found ->
                // Failsafe's JVMs take `prepare-agent`'s argLine when the build has no integration goal.
                val agentGoal = found.ownAgentGoal.takeIf { JacocoAdoption.executions(project, it).isNotEmpty() }
                    ?: JacocoAdoption.PREPARE_AGENT
                Run(
                    found.name, found.key, agentGoal,
                    DevelocityDetection.decline(project, found.execution, session)
                        ?: Declines.of(project, found.plugin, found.execution, session) ?: cached
                        ?: JacocoAdoption.decline(project, agentGoal, session),
                )
            }
            for ((agentGoal, sharing) in runs.groupBy(Run::agentGoal)) {
                // A declined execution keeps the agent its host configured, and so does every other
                // execution that agent reaches. In-process ones are reached by no agent.
                val blocking = sharing.firstOrNull { it.decline != null && it.decline.kind != ScopeOutcome.NotApplied.Kind.SUREFIRE_IN_PROCESS }
                val scoped = if (blocking == null && sharing.any { it.decline == null }) {
                    JacocoAdoption.adopt(project, agentGoal, facts.scope)
                } else {
                    null
                }
                for (run in sharing) {
                    val outcome = run.decline
                        ?: blocking?.let {
                            ScopeOutcome.NotApplied(
                                "its JaCoCo agent also reaches ${it.name}, which is declined, so it is left as configured",
                                requireNotNull(it.decline).kind,
                            )
                        }
                        ?: requireNotNull(scoped)
                    wired[run.key] = outcome
                    if (outcome is ScopeOutcome.NotApplied) {
                        log.warn("[yoriwake] ${run.name} declined, ${outcome.kind.token}: ${outcome.detail}; every test runs and nothing is captured")
                    } else {
                        log.debug("[yoriwake] ${run.name}: scope $outcome")
                    }
                }
            }
        }
        if (wired.values.any { it !is ScopeOutcome.NotApplied }) {
            val packages = facts.scope.orEmpty().map { it.removeSuffix("*").removeSuffix(".") }.filter(String::isNotEmpty)
            session.request.executionListener = ChainedListener(session.request.executionListener, Recording(session, wired, packages))
        }
    }

    /** A test execution, the JaCoCo goal whose agent reaches its JVMs, and why it is declined, if it is. */
    private class Run(val name: String, val key: String, val agentGoal: String, val decline: ScopeOutcome.NotApplied?)

    /** For the functional suite, which runs Maven 4 before its release. */
    private fun allowsPreRelease(session: MavenSession) =
        (session.userProperties.getProperty(PRE_RELEASE_PROPERTY) ?: session.systemProperties.getProperty(PRE_RELEASE_PROPERTY)) == "true"

    private companion object {
        const val MAVEN_FLOOR = "3.9.0"
        const val PRE_RELEASE_PROPERTY = "yoriwake.internal.mavenPreRelease"

        /** An alpha, beta or release candidate: a version with a qualifier after its numbers. */
        val PRE_RELEASE = Regex("""\d+(\.\d+)*-.+""")

        val log = LoggerFactory.getLogger(YoriwakeLifecycle::class.java)
    }
}
