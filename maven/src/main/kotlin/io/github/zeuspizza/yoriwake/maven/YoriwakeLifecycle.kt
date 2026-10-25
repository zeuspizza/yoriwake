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
        for (project in session.projects) {
            val outcomes = mutableMapOf<String, ScopeOutcome>()
            for ((key, testGoal, ownGoal) in TEST_PLUGINS) {
                val plugin = project.getPlugin(key) ?: continue
                // Failsafe's JVMs take `prepare-agent`'s argLine when the build has no integration goal.
                val agentGoal = ownGoal.takeIf { JacocoAdoption.execution(project, it) != null } ?: JacocoAdoption.PREPARE_AGENT
                for (execution in plugin.executions.filter { testGoal in it.goals }) {
                    val name = "${project.artifactId} ${plugin.artifactId.removePrefix("maven-").removeSuffix("-plugin")}:$testGoal (${execution.id})"
                    val outcome = Declines.of(plugin, execution, session)
                        ?: JacocoAdoption.decline(project, agentGoal, session)
                        ?: outcomes.getOrPut(agentGoal) { JacocoAdoption.adopt(project, agentGoal, facts.scope) }
                    if (outcome is ScopeOutcome.NotApplied) {
                        log.warn("[yoriwake] $name declined, ${outcome.kind.token}: ${outcome.detail}; every test runs and nothing is captured")
                    } else {
                        log.debug("[yoriwake] $name: scope $outcome")
                    }
                }
            }
        }
    }

    /** For the functional suite, which runs Maven 4 before its release. */
    private fun allowsPreRelease(session: MavenSession) =
        (session.userProperties.getProperty(PRE_RELEASE_PROPERTY) ?: session.systemProperties.getProperty(PRE_RELEASE_PROPERTY)) == "true"

    private companion object {
        const val MAVEN_FLOOR = "3.9.0"
        const val PRE_RELEASE_PROPERTY = "yoriwake.internal.mavenPreRelease"

        /** An alpha, beta or release candidate: a version with a qualifier after its numbers. */
        val PRE_RELEASE = Regex("""\d+(\.\d+)*-.+""")

        /** Each test plugin, the goal that runs its tests, and the JaCoCo goal whose agent reaches them. */
        val TEST_PLUGINS = listOf(
            Triple("org.apache.maven.plugins:maven-surefire-plugin", "test", JacocoAdoption.PREPARE_AGENT),
            Triple("org.apache.maven.plugins:maven-failsafe-plugin", "integration-test", JacocoAdoption.PREPARE_AGENT_INTEGRATION),
        )

        val log = LoggerFactory.getLogger(YoriwakeLifecycle::class.java)
    }
}
